// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

import java.net.URI
import org.apache.logging.log4j.LogManager
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.utility.DockerImageName

/** Definition of a service to deploy. */
data class ServiceSpec(
    internal val name: String,
    internal val services: List<String>,
    internal val envs: Map<String, String>,
    internal val skipRegistration: Boolean,
) {

  companion object {
    private val LOG = LogManager.getLogger(RestateDeployer::class.java)

    fun builder(name: String): Builder {
      return Builder(name)
    }

    fun defaultBuilder(): Builder {
      return Builder(DEFAULT_SERVICE_NAME)
    }

    const val DEFAULT_SERVICE_NAME = "default-service"
  }

  data class Builder(
      private var name: String,
      var services: List<String> = listOf(),
      var envs: Map<String, String> = mapOf(),
      private var skipRegistration: Boolean = false,
  ) {

    fun withServices(vararg svcs: String) = apply { this.services += svcs.asList() }

    fun withEnv(key: String, value: String) = apply { this.envs += mapOf(key to value) }

    fun withEnvs(envs: Map<String, String>) = apply { this.envs += envs }

    fun skipRegistration() = apply { this.skipRegistration = true }

    fun build() = ServiceSpec(name, services, envs, skipRegistration)
  }

  internal fun toHostNameContainer(
      config: RestateDeployerConfig,
      network: Network,
      restateURI: String,
  ): Pair<String, ServiceDeploymentContainer>? {
    LOG.info("Service spec {} will use services {}", name, services)
    val servicesEnv = services.joinToString(separator = ",")
    val totalEnvs = envs + mapOf("SERVICES" to servicesEnv)

    return when (val serviceConfig = config.getServiceDeploymentConfig(name)) {
      is ContainerServiceDeploymentConfig -> {
        name to
            ServiceDeploymentContainer(
                DockerImageName.parse(serviceConfig.imageName),
                name,
                network,
                restateURI,
                totalEnvs + serviceConfig.additionalEnvs)
      }
      is LocalForwardServiceDeploymentConfig -> {
        Testcontainers.exposeHostPorts(serviceConfig.port)
        LOG.warn(
            """
              Service spec '$name' won't deploy a container, but will use locally running service deployment:
              * Should be available at 'localhost:${serviceConfig.port}'
              * Should be configured with env variables ${totalEnvs}
          """
                .trimIndent())
        null
      }
    }
  }

  internal fun toHostnamePort(config: RestateDeployerConfig): Pair<String, Int> {
    return when (val serviceConfig = config.getServiceDeploymentConfig(name)) {
      is ContainerServiceDeploymentConfig -> {
        name to 9080
      }
      is LocalForwardServiceDeploymentConfig ->
          GenericContainer.INTERNAL_HOST_HOSTNAME to serviceConfig.port
    }
  }

  internal fun getEndpointUrl(config: RestateDeployerConfig): URI {
    val hostNamePort = toHostnamePort(config)
    return URI.create("http://${hostNamePort.first}:${hostNamePort.second}/")
  }
}
