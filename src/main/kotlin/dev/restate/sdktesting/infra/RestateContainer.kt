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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.use
import org.apache.logging.log4j.CloseableThreadContext
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
    val hostname: String,
    network: Network,
    envs: Map<String, String>,
    configSchema: RestateConfigSchema?,
    copyToContainer: List<Pair<String, Transferable>>,
    enableLocalPortForward: Boolean = true,
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
                            .withRate(200, TimeUnit.MILLISECONDS)
                            .withConstantThroughput()
                            .build()))
            .withStrategy(
                Wait.forHttp("/health")
                    .forPort(RUNTIME_ADMIN_ENDPOINT_PORT)
                    .withRateLimiter(
                        RateLimiterBuilder.newBuilder()
                            .withRate(200, TimeUnit.MILLISECONDS)
                            .withConstantThroughput()
                            .build()))
            .withStartupTimeout(120.seconds.toJavaDuration())

    fun createRestateContainers(
        config: RestateDeployerConfig,
        network: Network,
        envs: Map<String, String>,
        configSchema: RestateConfigSchema?,
        copyToContainer: List<Pair<String, Transferable>>,
        nodes: Int
    ): List<RestateContainer> {
      val clusterId = UUID.randomUUID().toString()
      val replicationProperty = if (nodes == 1) 1 else 2
      val effectiveEnvs =
          envs +
              mapOf<String, String>(
                  "RESTATE_CLUSTER_NAME" to clusterId,
                  "RESTATE_AUTO_PROVISION" to "false",
                  "RESTATE_BIFROST__DEFAULT_PROVIDER" to "replicated",
                  "RESTATE_BIFROST__REPLICATED_LOGLET__DEFAULT_LOG_REPLICATION" to
                      replicationProperty.toString(),
                  "RESTATE_ROLES" to "[worker,log-server,admin,metadata-server]",
                  "RESTATE_METADATA_SERVER__TYPE" to "replicated",
                  "RESTATE_METADATA_CLIENT__ADDRESSES" to
                      "[http://$RESTATE_RUNTIME:$RUNTIME_NODE_PORT]",
              )

      return listOf(
          RestateContainer(
              config,
              // First node has the default hostname as usual, this makes sure containers
              // port injection annotations still work.
              RESTATE_RUNTIME,
              network,
              effectiveEnvs +
                  mapOf(
                      "RESTATE_AUTO_PROVISION" to "true",
                      "RESTATE_ADVERTISED_ADDRESS" to "http://$RESTATE_RUNTIME:$RUNTIME_NODE_PORT"),
              configSchema,
              copyToContainer)) +
          (1.rangeUntil(nodes)).map {
            RestateContainer(
                config,
                "$RESTATE_RUNTIME-$it",
                network,
                effectiveEnvs +
                    mapOf(
                        "RESTATE_ADVERTISED_ADDRESS" to
                            "http://$RESTATE_RUNTIME-$it:$RUNTIME_NODE_PORT"),
                configSchema,
                copyToContainer,
                // Only the leader gets the privilege of local port forwarding
                enableLocalPortForward = false)
          }
    }
  }

  init {
    CloseableThreadContext.put("containerHostname", hostname).use {
      withImagePullPolicy(config.imagePullPolicy.toTestContainersImagePullPolicy())

      withEnv("RESTATE_NETWORKING__HTTP2_KEEP_ALIVE_INTERVAL", "4s")
      withEnv("RESTATE_NETWORKING__HTTP2_KEEP_ALIVE_TIMEOUT", "20s")
      withEnv(envs)
      // These envs should not be overriden by envs
      withEnv("RESTATE_ADMIN__BIND_ADDRESS", "0.0.0.0:$RUNTIME_ADMIN_ENDPOINT_PORT")
      withEnv("RESTATE_INGRESS__BIND_ADDRESS", "0.0.0.0:$RUNTIME_INGRESS_ENDPOINT_PORT")
      withEnv("DO_NOT_TRACK", "true")

      this.network = network
      this.networkAliases = arrayListOf(hostname)
      withCreateContainerCmdModifier { it.withHostName(hostname) }

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

      if (enableLocalPortForward && config.localAdminPort != null) {
        LOG.info("Going to expose Admin port on 'localhost:{}'", config.localAdminPort)
        super.addFixedExposedPort(config.localAdminPort, RUNTIME_ADMIN_ENDPOINT_PORT)
      } else {
        addExposedPort(RUNTIME_ADMIN_ENDPOINT_PORT)
      }
      if (enableLocalPortForward && config.localIngressPort != null) {
        LOG.info("Going to expose Admin port on 'localhost:{}'", config.localIngressPort)
        super.addFixedExposedPort(config.localIngressPort, RUNTIME_INGRESS_ENDPOINT_PORT)
      } else {
        addExposedPort(RUNTIME_INGRESS_ENDPOINT_PORT)
      }
      if (enableLocalPortForward && config.localNodePort != null) {
        LOG.info("Going to expose node port on 'localhost:{}'", config.localNodePort)
        super.addFixedExposedPort(config.localNodePort, RUNTIME_NODE_PORT)
      } else {
        addExposedPort(RUNTIME_NODE_PORT)
      }
    }
  }

  fun configureLogger(testReportDir: String): RestateContainer {
    this.withLogConsumer(ContainerLogger(testReportDir, hostname))
    return this
  }

  fun waitStartup() {
    WAIT_STARTUP_STRATEGY.waitUntilReady(this)
  }

  fun dumpConfiguration() {
    check(isRunning) { "The container is not running, can't dump configuration" }
    dockerClient.killContainerCmd(containerId).withSignal("SIGUSR1").exec()
  }

  override fun getContainerInfo(): InspectContainerResponse {
    // We override container info to avoid getting outdated info when restarted
    return currentContainerInfo
  }
}
