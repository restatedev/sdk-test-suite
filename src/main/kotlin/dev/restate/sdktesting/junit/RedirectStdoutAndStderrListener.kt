/*
 * Copyright 2015-2024 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */
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
