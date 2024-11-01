// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
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
  private var logStream: BufferedWriter? = null

  override fun accept(frame: OutputFrame) {
    when (frame.type) {
      OutputFrame.OutputType.STDOUT -> {
        resolveStream().write(frame.utf8String)
      }
      OutputFrame.OutputType.STDERR -> {
        resolveStream().write(frame.utf8String)
      }
      else -> {
        logStream?.close()
        logStream = null
        startCount++
      }
    }
  }

  private fun resolveStream(): BufferedWriter {
    if (logStream == null) {
      logStream = newStream(testReportDirectory, loggerName)
    }
    return logStream!!
  }

  private fun newStream(
      testReportDirectory: String,
      loggerName: String,
  ): BufferedWriter {
    val path = Path.of(testReportDirectory, "${loggerName}_${startCount}.log")
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
