package dev.restate.sdktesting.junit

import dev.restate.sdktesting.infra.BaseRestateDeployerExtension
import dev.restate.sdktesting.infra.getGlobalConfig
import dev.restate.sdktesting.infra.registerGlobalConfig
import java.io.PrintWriter
import java.nio.file.Path
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration.Companion.milliseconds
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.ThreadContext
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration
import org.junit.platform.engine.Filter
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.LauncherConstants
import org.junit.platform.launcher.TagFilter
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import org.junit.platform.launcher.listeners.TestExecutionSummary
import org.junit.platform.reporting.open.xml.OpenTestReportGeneratingListener

class TestSuite(
    val name: String,
    val additionalEnvs: Map<String, String>,
    val junitIncludeTags: String
) {
  fun runTests(
      baseReportDir: Path,
      filters: List<Filter<*>>,
      printToStdout: Boolean
  ): TestExecutionSummary {
    val reportDir = baseReportDir.resolve(name)

    println(
        """
            ========================= $name =========================
            Report directory: $reportDir
        """
            .trimIndent())

    // Apply additional runtime envs
    registerGlobalConfig(getGlobalConfig().copy(additionalRuntimeEnvs = additionalEnvs))

    // Prepare Log4j2 configuration
    Configurator.reconfigure(prepareLog4j2Config(reportDir, printToStdout))

    // Prepare launch request
    val request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("dev.restate.sdktesting.tests"))
            .filters(TagFilter.includeTags(junitIncludeTags))
            .filters(*filters.toTypedArray())
            // OpenXML reporting
            .configurationParameter("junit.platform.reporting.open.xml.enabled", "true")
            .configurationParameter("junit.platform.reporting.output.dir", reportDir.toString())
            // Redirect STDOUT/STDERR
            .configurationParameter(LauncherConstants.CAPTURE_STDOUT_PROPERTY_NAME, "true")
            .configurationParameter(LauncherConstants.CAPTURE_STDERR_PROPERTY_NAME, "true")
            // Config option used by RestateDeployer extensions
            .configurationParameter(
                BaseRestateDeployerExtension.REPORT_DIR_PROPERTY_NAME, reportDir.toString())
            .build()

    // Configure listeners
    val summaryListener = SummaryGeneratingListener()
    val xmlReportListener = OpenTestReportGeneratingListener()
    val redirectStdoutAndStderrListener =
        RedirectStdoutAndStderrListener(
            reportDir.resolve("testrunner.stdout"),
            reportDir.resolve("testrunner.stderr"),
            PrintWriter(System.err))
    val injectLoggingContextListener =
        object : TestExecutionListener {
          val TEST_NAME = "test"

          override fun executionStarted(testIdentifier: TestIdentifier) {
            val displayName =
                when (val source = testIdentifier.source.getOrNull()) {
                  is ClassSource -> source.className
                  is MethodSource -> "${source.className}#${source.methodName}"
                  else -> null
                }
            if (displayName != null) {
              ThreadContext.put(TEST_NAME, displayName)
            }
          }

          override fun executionFinished(
              testIdentifier: TestIdentifier,
              testExecutionResult: TestExecutionResult
          ) {
            ThreadContext.remove(TEST_NAME)
          }
        }

    // Launch
    LauncherFactory.openSession().use { session ->
      val launcher = session.launcher
      launcher.registerTestExecutionListeners(
          summaryListener,
          xmlReportListener,
          redirectStdoutAndStderrListener,
          injectLoggingContextListener)
      launcher.execute(request)
    }

    val report = summaryListener.summary!!

    println(
        """
            * Succeeded tests: ${report.testsSucceededCount} / ${report.testsStartedCount}
            * Succeeded test classes: ${report.containersSucceededCount - 1} / ${report.containersStartedCount - 1}
            * Execution time: ${report.timeFinished.milliseconds - report.timeStarted.milliseconds}
        """
            .trimIndent())
    val printWriter = PrintWriter(System.out)
    report.printFailuresTo(printWriter)

    return report
  }

  private fun prepareLog4j2Config(reportDir: Path, printToStdout: Boolean): BuiltConfiguration {
    val builder = ConfigurationBuilderFactory.newConfigurationBuilder()

    val layout = builder.newLayout("PatternLayout")
    layout.addAttribute("pattern", "%-4r [%t]%X %-5p %c - %m%n")

    val fileAppender = builder.newAppender("log", "File")
    fileAppender.addAttribute("fileName", reportDir.resolve("testrunner.log").toString())
    fileAppender.add(layout)

    val rootLogger = builder.newRootLogger(Level.INFO)
    rootLogger.add(builder.newAppenderRef("log"))

    val testContainersLogger = builder.newLogger("org.testcontainers", Level.INFO)
    testContainersLogger.add(builder.newAppenderRef("log"))
    testContainersLogger.addAttribute("additivity", false)

    val restateLogger = builder.newLogger("dev.restate", Level.DEBUG)
    restateLogger.add(builder.newAppenderRef("log"))
    restateLogger.addAttribute("additivity", false)

    if (printToStdout) {
      val consoleAppender = builder.newAppender("stdout", "Console")
      consoleAppender.add(layout)
      builder.add(consoleAppender)

      rootLogger.add(builder.newAppenderRef("stdout"))
      testContainersLogger.add(builder.newAppenderRef("stdout"))
      restateLogger.add(builder.newAppenderRef("stdout"))
    }

    builder.add(fileAppender)
    builder.add(rootLogger)
    builder.add(testContainersLogger)
    builder.add(restateLogger)

    return builder.build()
  }
}
