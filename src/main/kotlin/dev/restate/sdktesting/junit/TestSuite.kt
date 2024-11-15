// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.junit

import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import dev.restate.sdktesting.infra.BaseRestateDeployerExtension
import dev.restate.sdktesting.infra.getGlobalConfig
import dev.restate.sdktesting.infra.registerGlobalConfig
import dev.restate.sdktesting.junit.InjectLog4jContextListener.Companion.TEST_CLASS
import java.io.PrintWriter
import java.nio.file.Path
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration
import org.junit.platform.engine.Filter
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.*
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener

class TestSuite(
    val name: String,
    val additionalEnvs: Map<String, String>,
    val junitIncludeTags: String,
    val restateNodes: Int = 1
) {
  fun runTests(
      terminal: Terminal,
      baseReportDir: Path,
      filters: List<Filter<*>>,
      printToStdout: Boolean,
      parallel: Boolean
  ): ExecutionResult {
    val reportDir = baseReportDir.resolve(name)
    terminal.println(
        """
              |==== ${bold(name)}
              |🗈 Report directory: $reportDir
          """
            .trimMargin())

    // Prepare Log4j2 configuration
    val log4j2Configuration = prepareLog4j2Config(reportDir, printToStdout)
    Configurator.reconfigure(log4j2Configuration)

    // Apply additional runtime envs
    val restateDeployerConfig =
        getGlobalConfig().copy(additionalRuntimeEnvs = additionalEnvs, restateNodes = restateNodes)
    registerGlobalConfig(restateDeployerConfig)

    // Prepare launch request
    var builder =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("dev.restate.sdktesting.tests"))
            .filters(TagFilter.includeTags(junitIncludeTags))
            .filters(*filters.toTypedArray())
            // Redirect STDOUT/STDERR
            .configurationParameter(LauncherConstants.CAPTURE_STDOUT_PROPERTY_NAME, "true")
            .configurationParameter(LauncherConstants.CAPTURE_STDERR_PROPERTY_NAME, "true")
            // Config option used by RestateDeployer extensions
            .configurationParameter(
                BaseRestateDeployerExtension.REPORT_DIR_PROPERTY_NAME, reportDir.toString())
            .configurationParameter(
                "junit.jupiter.execution.parallel.mode.classes.default",
                if (parallel) "concurrent" else "same_thread")

    // Disable lifecycle timeout
    if (restateDeployerConfig.retainAfterEnd) {
      builder =
          builder.configurationParameter(
              "junit.jupiter.execution.timeout.lifecycle.method.default", "360m")
    }

    // Reduce parallelism in three nodes setup
    if (restateDeployerConfig.restateNodes > 1 && parallel) {
      builder =
          builder.configurationParameter(
              "junit.jupiter.execution.parallel.config.dynamic.factor", "0.5")
    }

    val request = builder.build()

    // Configure listeners
    val errWriter = PrintWriter(System.err)
    val executionResultCollector = ExecutionResultCollector(name)
    // TODO replace this with our own xml writer
    val xmlReportListener = LegacyXmlReportGeneratingListener(reportDir, errWriter)
    val redirectStdoutAndStderrListener =
        RedirectStdoutAndStderrListener(
            reportDir.resolve("testrunner.stdout"),
            reportDir.resolve("testrunner.stderr"),
            errWriter)
    val logTestEventsListener = LogTestEventsToTerminalListener(name, terminal)
    val injectLoggingContextListener = InjectLog4jContextListener(name)

    // Launch
    LauncherFactory.openSession().use { session ->
      val launcher = session.launcher
      launcher.registerTestExecutionListeners(
          executionResultCollector,
          logTestEventsListener,
          xmlReportListener,
          redirectStdoutAndStderrListener,
          injectLoggingContextListener)
      launcher.execute(request)
    }

    val report = executionResultCollector.results

    report.printShortSummary(terminal)

    return report
  }

  private fun prepareLog4j2Config(reportDir: Path, printToStdout: Boolean): BuiltConfiguration {
    val builder = ConfigurationBuilderFactory.newConfigurationBuilder()

    val layout =
        builder
            .newLayout("PatternLayout")
            .addAttribute(
                "pattern",
                "%d{ISO8601} %-5p [%X{test_class}][%t]%notEmpty{[%X{containerHostname}]} %c{1.2.*} - %m%n")

    val testRunnerFileAppender =
        builder
            .newAppender("testRunnerLog", "File")
            .addAttribute("fileName", reportDir.resolve("testrunner.log").toString())
            .add(layout)
    val nullAppender = builder.newAppender("nullAppender", "Null")

    val routing =
        builder
            .newAppender("routingAppender", "Routing")
            // If you wanna try to figure out what's going on here:
            // https://stackoverflow.com/questions/25114526/log4j2-how-to-write-logs-to-separate-files-for-each-user
            .addComponent(
                builder
                    .newComponent("Routes")
                    .addAttribute("pattern", "\${ctx:${TEST_CLASS}}")
                    .addComponent(
                        // Route for XML magicians
                        builder
                            .newComponent("Route")
                            .addComponent(
                                builder
                                    .newAppender("testRunnerLog-\${ctx:${TEST_CLASS}}", "File")
                                    .addAttribute(
                                        "fileName",
                                        "${reportDir}/\${ctx:${TEST_CLASS}}/testRunner.log")
                                    .add(layout)))
                    .addComponent(
                        // Default route to noop (still for XML magicians)
                        builder
                            .newComponent("Route")
                            .addAttribute("key", "\${ctx:${TEST_CLASS}}")
                            .addAttribute("ref", "nullAppender")))

    val restateLogger =
        builder
            .newLogger("dev.restate", Level.DEBUG)
            .add(builder.newAppenderRef("testRunnerLog"))
            .add(builder.newAppenderRef("routingAppender"))
            .addAttribute("additivity", false)

    val testContainersLogger =
        builder
            .newLogger("org.testcontainers", Level.TRACE)
            .add(builder.newAppenderRef("testRunnerLog"))
            .add(builder.newAppenderRef("routingAppender"))
            .addAttribute("additivity", false)

    val rootLogger =
        builder
            .newRootLogger(Level.WARN)
            .add(builder.newAppenderRef("testRunnerLog"))
            .add(builder.newAppenderRef("routingAppender"))

    if (printToStdout) {
      val consoleAppender = builder.newAppender("stdout", "Console").add(layout)
      builder.add(consoleAppender)

      rootLogger.add(builder.newAppenderRef("stdout"))
      restateLogger.add(builder.newAppenderRef("stdout"))
    }

    builder.add(testRunnerFileAppender)
    builder.add(nullAppender)
    builder.add(routing)
    builder.add(restateLogger)
    builder.add(testContainersLogger)
    builder.add(rootLogger)

    return builder.build()
  }
}
