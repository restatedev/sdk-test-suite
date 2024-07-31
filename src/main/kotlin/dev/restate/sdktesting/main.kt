package dev.restate.sdktesting

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.decodeFromStream
import com.charleskorn.kaml.encodeToStream
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.cooccurring
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import dev.restate.sdktesting.infra.*
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
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.Serializable
import org.junit.platform.engine.Filter
import org.junit.platform.engine.discovery.ClassNameFilter
import org.junit.platform.engine.support.descriptor.ClassSource

data class CommonConfig(var verbose: Boolean = false)

@Serializable data class ExclusionsFile(val exclusions: Map<String, List<String>> = emptyMap())

class RestateSdkTestSuite : CliktCommand() {
  val verbose by option().flag("--no-verbose", default = false)
  val commonConfig by findOrSetObject { CommonConfig() }

  override fun run() {
    // Disable log4j2 JMX, this prevents reconfiguration
    System.setProperty("log4j2.disable.jmx", "true")

    commonConfig.verbose = verbose
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
          .help("Pull policy used to pull containers required for testing")
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
  val testName by option().help("FQCN of the test to run for the given suite")
}

abstract class TestRunCommand(help: String) : CliktCommand(help) {
  val commonConfig by requireObject<CommonConfig>()
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
  val imageName by argument()

  override fun run() {
    val restateDeployerConfig =
        RestateDeployerConfig(
            mapOf(ServiceSpec.DEFAULT_SERVICE_NAME to ContainerServiceDeploymentConfig(imageName)))

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

    data class AggregateResults(
        var succededTests: Long = 0,
        var startedTests: Long = 0,
        var succeededClasses: Long = 0,
        var startedClasses: Long = 0,
        var totalDuration: Duration = Duration.ZERO
    )

    val aggregateResults = AggregateResults()
    val failedTests = mutableMapOf<String, List<String>>()
    for (testSuite in testSuites) {
      val exclusions = loadedExclusions.exclusions[testSuite.name] ?: emptyList()
      val exclusionsFilters = exclusions.map { ClassNameFilter.excludeClassNamePatterns(it) }
      val cliOptionFilter =
          filter?.testName?.let { listOf(ClassNameFilter.includeClassNamePatterns(it)) }
              ?: emptyList<Filter<*>>()

      val report =
          testSuite.runTests(
              testRunnerOptions.reportDir, exclusionsFilters + cliOptionFilter, false)

      aggregateResults.succededTests += report.testsSucceededCount
      aggregateResults.startedTests += report.testsStartedCount
      aggregateResults.succeededClasses +=
          report.containersSucceededCount - 1 // Package is a test container
      aggregateResults.startedClasses +=
          report.containersStartedCount - 1 // Package is a test container
      aggregateResults.totalDuration +=
          report.timeFinished.milliseconds - report.timeStarted.milliseconds

      if (report.failures.isNotEmpty() || exclusions.isNotEmpty()) {
        failedTests[testSuite.name] =
            report.failures
                .mapNotNull { it.testIdentifier.source.getOrNull() }
                .mapNotNull { if (it is ClassSource) it else null }
                .map { it.className!! } + exclusions
      }
    }

    FileOutputStream(testRunnerOptions.reportDir.resolve("exclusions.new.yaml").toFile()).use {
      Yaml.default.encodeToStream(ExclusionsFile(failedTests), it)
    }

    println(
        """
            ========================= Final results =========================
            All reports are available under: ${testRunnerOptions.reportDir}
            
            * Succeeded tests: ${aggregateResults.succededTests} / ${aggregateResults.startedTests}
            * Succeeded test classes: ${aggregateResults.succeededClasses} / ${aggregateResults.startedClasses}
            * Execution time: ${aggregateResults.totalDuration}
        """
            .trimIndent())

    if (failedTests.isNotEmpty()) {
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
  val testName by option().required().help("FQCN of the test to run for the given suite")
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

  override fun run() {
    // Register global config of the deployer
    val restateDeployerConfig =
        RestateDeployerConfig(
            localContainers.associate {
              it.first to LocalForwardServiceDeploymentConfig(it.second)
            })
    registerGlobalConfig(testRunnerOptions.applyToDeployerConfig(restateDeployerConfig))

    // Resolve test configurations
    val testSuite = TestSuites.resolveSuites(testSuite)[0]
    val testFilters = listOf(ClassNameFilter.includeClassNamePatterns(testName))

    val report = testSuite.runTests(testRunnerOptions.reportDir, testFilters, true)
    if (report.testsFailedCount != 0L) {
      // Exit
      exitProcess(1)
    }
  }
}

fun main(args: Array<String>) = RestateSdkTestSuite().subcommands(Run(), Debug()).main(args)
