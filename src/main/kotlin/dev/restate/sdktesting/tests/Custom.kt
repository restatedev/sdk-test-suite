// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.decodeFromStream
import dev.restate.sdktesting.infra.InjectAdminURI
import dev.restate.sdktesting.infra.InjectIngressURI
import dev.restate.sdktesting.infra.RestateDeployerExtension
import dev.restate.sdktesting.infra.ServiceSpec
import dev.restate.sdktesting.infra.getGlobalConfig
import java.io.FileInputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.Serializable
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

@Tag("customTests")
class Custom {

  @Serializable
  data class CustomTestConfig(val name: String, val command: String) {
    override fun toString(): String {
      return name
    }
  }

  @Serializable data class CustomTestsFile(val tests: List<CustomTestConfig>)

  companion object {
    private val LOG = LogManager.getLogger(Custom::class.java)

    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(ServiceSpec.defaultBuilder())
    }

    @JvmStatic
    fun configs(): Stream<CustomTestConfig> =
        (getGlobalConfig().customTestsFile?.let { file ->
              FileInputStream(file).use { Yaml.default.decodeFromStream<CustomTestsFile>(it).tests }
            } ?: emptyList())
            .stream()
  }

  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  @ParameterizedTest(name = "{0}")
  @MethodSource("configs")
  @Execution(ExecutionMode.SAME_THREAD)
  fun run(
      config: CustomTestConfig,
      @InjectAdminURI adminURI: URI,
      @InjectIngressURI ingressURI: URI,
  ) =
      runTest(5.minutes) {
        LOG.info("Running custom test '${config.name}': ${config.command}")

        val process =
            ProcessBuilder("sh", "-c", config.command)
                .apply {
                  environment()["RESTATE_INGRESS_URL"] = ingressURI.toString()
                  environment()["RESTATE_ADMIN_URL"] = adminURI.toString()
                }
                .redirectErrorStream(true)
                .start()

        // Pipe all output to the logger
        process.inputStream.bufferedReader().useLines { lines ->
          lines.forEach { line -> LOG.info("[${config.name}] $line") }
        }

        val exitCode = process.waitFor()
        LOG.info("Custom test '${config.name}' exited with code $exitCode")

        if (exitCode != 0) {
          throw AssertionError(
              "Command '${config.command}' failed with exit code $exitCode\n\nCheck the test runner log for more info.")
        }
      }
}
