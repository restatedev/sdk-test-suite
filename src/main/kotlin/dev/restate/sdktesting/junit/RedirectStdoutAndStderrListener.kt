// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.junit

import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.launcher.LauncherConstants
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan

/**
 * This is based on
 * https://github.com/junit-team/junit5/blob/af01ea8e6f52c44e458ff9b95cc6ccc7d0dc8ac6/junit-platform-console/src/main/java/org/junit/platform/console/tasks/RedirectStdoutAndStderrListener.java,
 * license EPL 2.0
 */
class RedirectStdoutAndStderrListener(
    private val stdoutOutputPath: Path,
    private val stderrOutputPath: Path,
    private val out: PrintWriter
) : TestExecutionListener {
  private val stdoutBuffer = StringWriter()
  private val stderrBuffer = StringWriter()

  override fun reportingEntryPublished(testIdentifier: TestIdentifier, entry: ReportEntry) {
    if (testIdentifier.isTest) {
      val redirectedStdoutContent = entry.keyValuePairs[LauncherConstants.STDOUT_REPORT_ENTRY_KEY]
      val redirectedStderrContent = entry.keyValuePairs[LauncherConstants.STDERR_REPORT_ENTRY_KEY]

      if (!redirectedStdoutContent.isNullOrEmpty()) {
        stdoutBuffer.append(redirectedStdoutContent)
      }
      if (!redirectedStderrContent.isNullOrEmpty()) {
        stderrBuffer.append(redirectedStderrContent)
      }
    }
  }

  override fun testPlanExecutionFinished(testPlan: TestPlan) {
    if (stdoutBuffer.buffer.length > 0) {
      flushBufferedOutputToFile(this.stdoutOutputPath, this.stdoutBuffer)
    }
    if (stderrBuffer.buffer.length > 0) {
      flushBufferedOutputToFile(this.stderrOutputPath, this.stderrBuffer)
    }
  }

  private fun flushBufferedOutputToFile(file: Path, buffer: StringWriter) {
    deleteFile(file)
    createFile(file)
    writeContentToFile(file, buffer.toString())
  }

  private fun writeContentToFile(file: Path, buffer: String) {
    try {
      Files.newBufferedWriter(file).use { fileWriter -> fileWriter.write(buffer) }
    } catch (e: IOException) {
      printException("Failed to write content to file: $file", e)
    }
  }

  private fun deleteFile(file: Path) {
    try {
      Files.deleteIfExists(file)
    } catch (e: IOException) {
      printException("Failed to delete file: $file", e)
    }
  }

  private fun createFile(file: Path) {
    try {
      Files.createFile(file)
    } catch (e: IOException) {
      printException("Failed to create file: $file", e)
    }
  }

  private fun printException(message: String, exception: Exception) {
    out.println(message)
    exception.printStackTrace(out)
  }
}
