// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.decodeFromStream
import com.charleskorn.kaml.encodeToStream
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.cooccurring
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import dev.restate.sdktesting.infra.*
import dev.restate.sdktesting.junit.ExecutionResult
import dev.restate.sdktesting.junit.TestSuites
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.jvm.optionals.getOrNull
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlinx.serialization.Serializable
import org.junit.platform.engine.Filter
import org.junit.platform.engine.discovery.ClassNameFilter
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource

@Serializable data class ExclusionsFile(val exclusions: Map<String, List<String>> = emptyMap())

class RestateSdkTestSuite : CliktCommand() {
  override fun run() {
    // Disable log4j2 JMX, this prevents reconfiguration
    System.setProperty("log4j2.disable.jmx", "true")
    // This is hours of debugging, don't touch it
    // tl;dr this makes sure a single log4j2 configuration exists for the whole JVM,
    // important to make Configurator.reconfigure work
    System.setProperty(
        "log4j2.contextSelector", "org.apache.logging.log4j.core.selector.BasicContextSelector")
    // The default keep alive time is way too long, and this is a problem when we stop and restart
    // containers.
    System.setProperty("jdk.httpclient.keepalive.timeout", "5")
    // The health check strategy uses the HttpUrlConnection which has no connect timeout by default.
    // Could have caused the health check to hang indefinitely.
    System.setProperty("sun.net.client.defaultConnectTimeout", "5000")
  }
}

class TestRunnerOptions : OptionGroup() {
  val restateContainerImage by
      option(envvar = "RESTATE_CONTAINER_IMAGE").help("Image used to run Restate")
  val reportDir by
      option(envvar = "TEST_REPORT_DIR").path().help("Base report directory").defaultLazy {
        defaultReportDirectory()
      }
  val imagePullPolicy by
      option()
          .enum<PullPolicy>()
          .help(
              "Pull policy used to pull containers required for testing. In case of ALWAYS, docker won't pull images with repository prefix restate.local or localhost")
          .default(PullPolicy.ALWAYS)

  fun applyToDeployerConfig(deployerConfig: RestateDeployerConfig): RestateDeployerConfig {
    var newConfig = deployerConfig
    if (restateContainerImage != null) {
      newConfig = newConfig.copy(restateContainerImage = restateContainerImage!!)
    }
    newConfig = newConfig.copy(imagePullPolicy = imagePullPolicy)
    return newConfig
  }

  private fun defaultReportDirectory(): Path {
    val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    return Path.of("test_report/${LocalDateTime.now().format(formatter)}").toAbsolutePath()
  }
}

class FilterOptions : OptionGroup() {
  val testSuite by
      option()
          .required()
          .help(
              "Test suite to run. Available: ${listOf("all") + TestSuites.allSuites().map { it.name }}")
  val testName by option().help("Name of the test to run for the given suite")
}

abstract class TestRunCommand(help: String) : CliktCommand(help) {
  val testRunnerOptions by TestRunnerOptions()
}

class Run :
    TestRunCommand(
        """
Run test suite, executing the service as container.
"""
            .trimIndent()) {
  val filter by FilterOptions().cooccurring()
  val exclusionsFile by option().help("File containing the excluded tests")
  val parallel by
      option(help = "Enable parallel testing")
          .help(
              "If set, runs tests in parallel. We suggest running tests sequentially when using podman")
          .flag("--sequential", default = true)
  val imageName by argument()

  override fun run() {
    val terminal = Terminal()

    val restateDeployerConfig =
        RestateDeployerConfig(
            mapOf(ServiceSpec.DEFAULT_SERVICE_NAME to ContainerServiceDeploymentConfig(imageName)),
        )

    // Register global config of the deployer
    registerGlobalConfig(testRunnerOptions.applyToDeployerConfig(restateDeployerConfig))

    // Resolve test configurations
    val testSuites = TestSuites.resolveSuites(filter?.testSuite)

    // Load exclusions file
    val loadedExclusions: ExclusionsFile =
        if (exclusionsFile != null) {
          FileInputStream(File(exclusionsFile!!)).use { Yaml.default.decodeFromStream(it) }
        } else {
          ExclusionsFile()
        }

    val reports = mutableListOf<ExecutionResult>()
    val newExclusions = mutableMapOf<String, List<String>>()
    var newFailures = false
    for (testSuite in testSuites) {
      val exclusions =
          (loadedExclusions.exclusions[testSuite.name] ?: emptyList()).map {
            testClassNameToFQCN(it)
          }
      val exclusionsFilters = exclusions.map { ClassNameFilter.excludeClassNamePatterns(it) }
      val cliOptionFilter =
          filter?.testName?.let {
            listOf(ClassNameFilter.includeClassNamePatterns(testClassNameToFQCN(it)))
          } ?: emptyList<Filter<*>>()

      val report =
          testSuite.runTests(
              terminal,
              testRunnerOptions.reportDir,
              exclusionsFilters + cliOptionFilter,
              false,
              parallel)

      reports.add(report)
      // No need to wait the end of the run for this
      report.printFailuresToFiles(testRunnerOptions.reportDir)
      val failures = report.failedTests
      if (failures.isNotEmpty() || exclusions.isNotEmpty()) {
        newExclusions[testSuite.name] =
            (failures
                    .mapNotNull { it.source.getOrNull() }
                    .mapNotNull {
                      when (it) {
                        is ClassSource -> it.className!!
                        is MethodSource -> it.className!!
                        else -> null
                      }
                    }
                    .distinct() + exclusions)
                .sorted()
      }
      if (failures.isNotEmpty()) {
        newFailures = true
      }
    }

    // Write out the exclusions file
    FileOutputStream(testRunnerOptions.reportDir.resolve("exclusions.new.yaml").toFile()).use {
      Yaml.default.encodeToStream(ExclusionsFile(newExclusions.toSortedMap()), it)
    }

    // Print final report
    val succeededTests = reports.sumOf { it.succeededTests }
    val executedTests = reports.sumOf { it.executedTests }
    val testsStyle = if (succeededTests == executedTests) green else red
    val testsInfoLine = testsStyle("""* Succeeded tests: $succeededTests / ${executedTests}""")

    val failedClasses = reports.sumOf { it.executedClasses - it.succeededClasses }
    val classesStyle = if (failedClasses != 0) red else green
    val classesInfoLine = classesStyle("""* Failed classes initialization: $failedClasses""")

    val totalDuration = reports.fold(Duration.ZERO) { d, res -> d + res.executionDuration }

    println(
        """
            ${bold("========================= Final results =========================")}
            🗈 Report directory: ${testRunnerOptions.reportDir}
            * Run test suites: ${reports.map { it.testSuite }}
            $testsInfoLine
            $classesInfoLine
            * Execution time: $totalDuration
        """
            .trimIndent())

    for (report in reports) {
      report.printFailuresToTerminal(terminal)
    }

    if (newFailures) {
      // Exit
      exitProcess(1)
    }
  }
}

class Debug :
    TestRunCommand(
        """
Run test suite, without executing the service inside a container.
"""
            .trimIndent()) {
  val testSuite by
      option()
          .default(TestSuites.DEFAULT_SUITE.name)
          .help("Test suite to run. Available: ${TestSuites.allSuites().map { it.name }}")
  val testName by option().required().help("Name of the test to run for the given suite")
  val localContainers by
      argument()
          .convert { localContainerSpec ->
            if (localContainerSpec.contains('=')) {
              localContainerSpec.split('=', limit = 2).let { it[0] to it[1].toInt() }
            } else {
              ServiceSpec.DEFAULT_SERVICE_NAME to localContainerSpec.toInt()
            }
          }
          .multiple(required = true)
          .help(
              "Local containers name=ports. Example: '9080' (for default-service container), 'otherContainer=9081'")
  val retainAfterEnd by
      option()
          .flag("--dont-retain-after-end", default = false)
          .help(
              "Retain the created docker network after the end of the test. You MUST manually clean it up afterwards!")
  val mountStateDirectory by
      option()
          .help(
              "Mount the given state directory as restate data when starting the runtime container")
  val localIngressPort by option().int().help("Ingress port to bind the restate container")
  val localAdminPort by option().int().help("Admin port to bind the restate container")
  val localNodePort by option().int().help("Node port to bind the restate container")

  override fun run() {
    val terminal = Terminal()

    // Register global config of the deployer
    val restateDeployerConfig =
        RestateDeployerConfig(
            localContainers.associate {
              it.first to LocalForwardServiceDeploymentConfig(it.second)
            },
            localAdminPort = this.localAdminPort,
            localIngressPort = this.localIngressPort,
            localNodePort = this.localNodePort,
            stateDirectoryMount = this.mountStateDirectory,
            retainAfterEnd = this.retainAfterEnd)
    registerGlobalConfig(testRunnerOptions.applyToDeployerConfig(restateDeployerConfig))

    if (restateDeployerConfig.retainAfterEnd) {
      // Disable ryuk, as it will otherwise cleanup the network after the JVM goes away.
      //      System.getenv().put("TESTCONTAINERS_RYUK_DISABLED", "true")

    }

    // Resolve test configurations
    val testSuite = TestSuites.resolveSuites(testSuite)[0]
    val testFilters =
        listOf(ClassNameFilter.includeClassNamePatterns(testClassNameToFQCN(testName)))

    val report = testSuite.runTests(terminal, testRunnerOptions.reportDir, testFilters, true, false)

    report.printFailuresToTerminal(terminal)
    report.printFailuresToFiles(testRunnerOptions.reportDir)

    if (report.failedTests.isNotEmpty()) {
      // Exit
      exitProcess(1)
    }
  }
}

fun main(args: Array<String>) = RestateSdkTestSuite().subcommands(Run(), Debug()).main(args)

private fun testClassNameToFQCN(className: String): String {
  if (className.contains('.')) {
    // Then it's FQCN
    return className
  }
  return "dev.restate.sdktesting.tests.${className}"
}
