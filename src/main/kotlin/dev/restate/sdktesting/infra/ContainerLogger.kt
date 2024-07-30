// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate e2e tests,
// which are released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/e2e/blob/main/LICENSE

package dev.restate.sdktesting.infra

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.function.Consumer
import org.testcontainers.containers.output.OutputFrame

/** Logger to dump to specific files the stdout and stderr of the containers */
internal class ContainerLogger(
    private val testReportDirectory: String,
    private val loggerName: String
) : Consumer<OutputFrame> {

  private var startCount = 0
  private var stdoutStream: BufferedWriter? = null
  private var stderrStream: BufferedWriter? = null

  override fun accept(frame: OutputFrame) {
    when (frame.type) {
      OutputFrame.OutputType.STDOUT -> {
        resolveStdoutStream().write(frame.utf8String)
      }
      OutputFrame.OutputType.STDERR -> {
        resolveStderrStream().write(frame.utf8String)
      }
      else -> {
        stdoutStream?.close()
        stderrStream?.close()
        stdoutStream = null
        stderrStream = null
        startCount++
      }
    }
  }

  private fun resolveStdoutStream(): BufferedWriter {
    if (stdoutStream == null) {
      stdoutStream = newStream(testReportDirectory, loggerName, "stdout")
    }
    return stdoutStream!!
  }

  private fun resolveStderrStream(): BufferedWriter {
    if (stderrStream == null) {
      stderrStream = newStream(testReportDirectory, loggerName, "stderr")
    }
    return stderrStream!!
  }

  private fun newStream(
      testReportDirectory: String,
      loggerName: String,
      type: String
  ): BufferedWriter {
    val path = Path.of(testReportDirectory, "${loggerName}_${startCount}_${type}.log")
    val fileExists = Files.exists(path)

    val writer =
        Files.newBufferedWriter(
            path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)

    if (fileExists) {
      writer.newLine()
      writer.newLine()
    }

    writer.write("========================= START LOG =========================\n")

    return writer
  }
}
