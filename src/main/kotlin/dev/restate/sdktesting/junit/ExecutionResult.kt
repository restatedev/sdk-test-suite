// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.junit

import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import java.io.PrintWriter
import java.lang.IllegalStateException
import java.util.*
import kotlin.math.min
import kotlin.time.TimeSource
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan

class ExecutionResult(
    val testSuite: String,
    private val testPlan: TestPlan,
    private val classesResults: Map<TestIdentifier, TestResult>,
    private val testResults: Map<TestIdentifier, TestResult>,
    timeStarted: TimeSource.Monotonic.ValueTimeMark,
    timeFinished: TimeSource.Monotonic.ValueTimeMark
) {

  val succeededTests = testResults.values.count { it is Succeeded }
  val executedTests = testResults.size
  val succeededClasses = classesResults.values.count { it is Succeeded }
  val executedClasses = classesResults.size
  val executionDuration = timeFinished - timeStarted

  companion object {
    private const val TAB = "  "
    private const val DOUBLE_TAB = TAB + TAB
    private const val DEFAULT_MAX_STACKTRACE_LINES = 15

    private const val CAUSED_BY = "Caused by: "
    private const val SUPPRESSED = "Suppressed: "
    private const val CIRCULAR = "Circular reference: "
  }

  sealed interface TestResult

  data object Succeeded : TestResult

  data object Aborted : TestResult

  data class Failed(val throwable: Throwable?) : TestResult

  val failedTests: List<TestIdentifier>
    get() {
      return classesResults
          .toList()
          .filter { it.second is Failed || it.second is Aborted }
          .map { it.first } +
          testResults
              .toList()
              .filter { it.second is Failed || it.second is Aborted }
              .map { it.first }
    }

  fun printShortSummary(terminal: Terminal) {
    // Compute test counters
    val testsStyle = if (succeededTests == testResults.size) green else red
    val testsInfoLine = testsStyle("""* Succeeded tests: $succeededTests / ${executedTests}""")

    // Compute classes counters
    val failedClasses = executedClasses - succeededClasses
    val classesStyle = if (failedClasses != 0) red else green
    val classesInfoLine = classesStyle("""* Failed classes initialization: $failedClasses""")

    // Terminal print
    terminal.println(
        """
        ${bold("==== $testSuite results")}
        $testsInfoLine
        $classesInfoLine
        * Execution time: $executionDuration
        """
            .trimIndent())
  }

  fun printFailuresTo(terminal: Terminal, maxStackTraceLines: Int = DEFAULT_MAX_STACKTRACE_LINES) {

    val classesFailures =
        this.classesResults.toList().filter { it.second is Aborted || it.second is Failed }
    val testsFailures =
        this.classesResults.toList().filter { it.second is Aborted || it.second is Failed }

    fun printFailures(failureList: List<Pair<TestIdentifier, TestResult>>) {
      for (failure in failureList) {
        terminal.println("$TAB${describeTestIdentifier(testSuite, testPlan, failure.first)}")
        describeTestIdentifierSource(terminal, failure.first)
        when (failure.second) {
          Aborted -> terminal.println("${DOUBLE_TAB}=> ABORTED")
          is Failed -> {
            val throwable = (failure.second as Failed).throwable
            if (throwable == null) {
              terminal.println("${DOUBLE_TAB}=> UNKNOWN FAILURE")
            } else {
              terminal.println("$DOUBLE_TAB=> $throwable")
              printStackTrace(PrintWriter(System.out), throwable, maxStackTraceLines)
            }
          }
          Succeeded -> throw IllegalStateException()
        }
      }
    }

    if (classesFailures.isEmpty() && testsFailures.isEmpty()) {
      return
    }

    terminal.println((red + bold)("== '$testSuite' FAILURES"))

    if (classesFailures.isNotEmpty()) {
      terminal.println("Classes initialization failures ${red(classesFailures.size.toString())}:")
      printFailures(classesFailures)
    }

    if (testsFailures.isNotEmpty()) {
      terminal.println("Test failures ${red(testsFailures.size.toString())}:")
      printFailures(testsFailures)
    }
  }

  private fun describeTestIdentifierSource(terminal: Terminal, testIdentifier: TestIdentifier) {
    testIdentifier.source.ifPresent { terminal.println("${DOUBLE_TAB}$it") }
  }

  private fun printStackTrace(writer: PrintWriter, throwable: Throwable, max: Int) {
    var max = max
    if (throwable.cause != null ||
        (throwable.suppressed != null && throwable.suppressed.size > 0)) {
      max = max / 2
    }
    printStackTrace(writer, arrayOf(), throwable, "", DOUBLE_TAB + " ", HashSet(), max)
    writer.flush()
  }

  private fun printStackTrace(
      writer: PrintWriter,
      parentTrace: Array<StackTraceElement>?,
      throwable: Throwable?,
      caption: String,
      indentation: String,
      seenThrowables: MutableSet<Throwable?>,
      max: Int
  ) {
    if (seenThrowables.contains(throwable)) {
      writer.printf("%s%s[%s%s]%n", indentation, TAB, CIRCULAR, throwable)
      return
    }
    seenThrowables.add(throwable)

    val trace = throwable!!.stackTrace
    if (parentTrace != null && parentTrace.size > 0) {
      writer.printf("%s%s%s%n", indentation, caption, throwable)
    }
    val duplicates = numberOfCommonFrames(trace, parentTrace)
    val numDistinctFrames = trace.size - duplicates
    val numDisplayLines = min(numDistinctFrames.toDouble(), max.toDouble()).toInt()
    for (i in 0 until numDisplayLines) {
      writer.printf("%s%s%s%n", indentation, TAB, trace[i])
    }
    if (trace.size > max || duplicates != 0) {
      writer.printf("%s%s%s%n", indentation, TAB, "[...]")
    }

    for (suppressed in throwable.suppressed) {
      printStackTrace(writer, trace, suppressed, SUPPRESSED, indentation + TAB, seenThrowables, max)
    }
    if (throwable.cause != null) {
      printStackTrace(writer, trace, throwable.cause, CAUSED_BY, indentation, seenThrowables, max)
    }
  }

  private fun numberOfCommonFrames(
      currentTrace: Array<StackTraceElement>,
      parentTrace: Array<StackTraceElement>?
  ): Int {
    var currentIndex = currentTrace.size - 1
    var parentIndex = parentTrace!!.size - 1
    while (currentIndex >= 0 && parentIndex >= 0) {
      if (currentTrace[currentIndex] != parentTrace[parentIndex]) {
        break
      }
      currentIndex--
      parentIndex--
    }
    return currentTrace.size - 1 - currentIndex
  }
}
