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
import dev.restate.sdktesting.infra.runtimeconfig.RestateConfigSchema
import java.io.File
import org.apache.logging.log4j.LogManager
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.SelinuxContext
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

class RestateContainer(
    config: RestateDeployerConfig,
    envs: Map<String, String>,
    configSchema: RestateConfigSchema?,
    copyToContainer: List<Pair<String, Transferable>>
) : GenericContainer<RestateContainer>(DockerImageName.parse(config.restateContainerImage)) {
  companion object {
    private val LOG = LogManager.getLogger(RestateContainer::class.java)

    private val TOML_MAPPER = ObjectMapper(TomlFactory())
  }

  init {
    LOG.debug("Using runtime image '{}'", config.restateContainerImage)

    withEnv(envs)
    // These envs should not be overriden by envs
    withEnv("RESTATE_ADMIN__BIND_ADDRESS", "0.0.0.0:$RUNTIME_META_ENDPOINT_PORT")
    withEnv("RESTATE_INGRESS__BIND_ADDRESS", "0.0.0.0:$RUNTIME_INGRESS_ENDPOINT_PORT")

    withNetwork(network)
    withNetworkAliases(RESTATE_RUNTIME)
    withStartupAttempts(3) // For podman

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
      super.addFixedExposedPort(config.localAdminPort, RUNTIME_META_ENDPOINT_PORT)
    }
    if (config.localIngressPort != null) {
      super.addFixedExposedPort(config.localIngressPort, RUNTIME_INGRESS_ENDPOINT_PORT)
    }
  }

  fun configureLogger(testReportDir: String): RestateContainer {
    this.withLogConsumer(ContainerLogger(testReportDir, "restate-runtime"))
    return this
  }
}
