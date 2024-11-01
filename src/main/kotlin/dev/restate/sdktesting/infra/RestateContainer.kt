// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.toml.TomlFactory
import com.github.dockerjava.api.command.InspectContainerResponse
import dev.restate.sdktesting.infra.runtimeconfig.RestateConfigSchema
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import org.apache.logging.log4j.LogManager
import org.rnorth.ducttape.ratelimits.RateLimiterBuilder
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.SelinuxContext
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

class RestateContainer(
    config: RestateDeployerConfig,
    network: Network,
    envs: Map<String, String>,
    configSchema: RestateConfigSchema?,
    copyToContainer: List<Pair<String, Transferable>>
) : GenericContainer<RestateContainer>(DockerImageName.parse(config.restateContainerImage)) {
  companion object {
    private val LOG = LogManager.getLogger(RestateContainer::class.java)
    private val TOML_MAPPER = ObjectMapper(TomlFactory())

    private val WAIT_STARTUP_STRATEGY =
        WaitAllStrategy()
            .withStrategy(
                Wait.forHttp("/restate/health")
                    .forPort(RUNTIME_INGRESS_ENDPOINT_PORT)
                    .withRateLimiter(
                        RateLimiterBuilder.newBuilder()
                            .withRate(100, TimeUnit.MILLISECONDS)
                            .withConstantThroughput()
                            .build())
                    .withStartupTimeout(20.seconds.toJavaDuration()))
            .withStrategy(
                Wait.forHttp("/health")
                    .forPort(RUNTIME_ADMIN_ENDPOINT_PORT)
                    .withRateLimiter(
                        RateLimiterBuilder.newBuilder()
                            .withRate(100, TimeUnit.MILLISECONDS)
                            .withConstantThroughput()
                            .build())
                    .withStartupTimeout(20.seconds.toJavaDuration()))
  }

  init {
    LOG.debug("Using runtime image '{}'", config.restateContainerImage)

    withImagePullPolicy(config.imagePullPolicy.toTestContainersImagePullPolicy())

    withEnv(envs)
    // These envs should not be overriden by envs
    withEnv("RESTATE_ADMIN__BIND_ADDRESS", "0.0.0.0:$RUNTIME_ADMIN_ENDPOINT_PORT")
    withEnv("RESTATE_INGRESS__BIND_ADDRESS", "0.0.0.0:$RUNTIME_INGRESS_ENDPOINT_PORT")

    this.network = network
    this.networkAliases = arrayListOf(RESTATE_RUNTIME)
    withStartupAttempts(3) // For podman
    waitingFor(WAIT_STARTUP_STRATEGY)

    if (config.stateDirectoryMount != null) {
      val stateDir = File(config.stateDirectoryMount)
      stateDir.mkdirs()

      LOG.debug("Mounting state directory to '{}'", stateDir.toPath())
      addFileSystemBind(stateDir.toString(), "/state", BindMode.READ_WRITE, SelinuxContext.SINGLE)
    }
    withEnv("RESTATE_BASE_DIR", "/state")

    if (configSchema != null) {
      withCopyToContainer(
          Transferable.of(TOML_MAPPER.writeValueAsBytes(configSchema)), "/config.toml")
      withEnv("RESTATE_CONFIG", "/config.toml")
    }

    for (file in copyToContainer) {
      withCopyToContainer(file.second, file.first)
    }

    if (config.localAdminPort != null) {
      LOG.info("Going to expose Admin port on localhost:{}", config.localAdminPort)
      super.addFixedExposedPort(config.localAdminPort, RUNTIME_ADMIN_ENDPOINT_PORT)
    } else {
      addExposedPort(RUNTIME_ADMIN_ENDPOINT_PORT)
    }
    if (config.localIngressPort != null) {
      LOG.info("Going to expose Admin port on localhost:{}", config.localIngressPort)
      super.addFixedExposedPort(config.localIngressPort, RUNTIME_INGRESS_ENDPOINT_PORT)
    } else {
      addExposedPort(RUNTIME_INGRESS_ENDPOINT_PORT)
    }
  }

  fun configureLogger(testReportDir: String): RestateContainer {
    this.withLogConsumer(ContainerLogger(testReportDir, "restate-runtime"))
    return this
  }

  fun waitStartup() {
    WAIT_STARTUP_STRATEGY.waitUntilReady(this)
  }

  override fun getContainerInfo(): InspectContainerResponse {
    // We override container info to avoid getting outdated info when restarted
    val containerId = this.containerId
    return dockerClient.inspectContainerCmd(containerId).exec()
  }
}
