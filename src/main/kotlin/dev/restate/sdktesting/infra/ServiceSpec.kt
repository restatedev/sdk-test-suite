// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate e2e tests,
// which are released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/e2e/blob/main/LICENSE

package dev.restate.sdktesting.infra

import java.net.URL
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/** Definition of a service to deploy. */
data class ServiceSpec(
    internal val name: String,
    internal val envs: Map<String, String>,
    internal val skipRegistration: Boolean,
) {

  companion object {
    fun builder(name: String): Builder {
      return Builder(name)
    }

    const val DEFAULT_SERVICE_NAME = "default-service"
    val DEFAULT = builder(DEFAULT_SERVICE_NAME).build()
  }

  data class Builder(
      private var name: String,
      private var envs: MutableMap<String, String> = mutableMapOf(),
      private var skipRegistration: Boolean = false,
  ) {

    fun withEnv(key: String, value: String) = apply { this.envs[key] = value }

    fun withEnvs(envs: Map<String, String>) = apply { this.envs.putAll(envs) }

    fun skipRegistration() = apply { this.skipRegistration = true }

    fun build() = ServiceSpec(name, envs, skipRegistration)
  }

  internal fun toHostNameContainer(
      config: RestateDeployerConfig
  ): Pair<String, GenericContainer<*>>? {
    return when (val serviceConfig = config.getServiceDeploymentConfig(name)) {
      is ContainerServiceDeploymentConfig -> {
        name to
            GenericContainer(DockerImageName.parse(serviceConfig.imageName))
                .withEnv("PORT", "9080")
                .withEnv(envs)
      }
      is LocalForwardServiceDeploymentConfig -> {
        Testcontainers.exposeHostPorts(serviceConfig.port)
        println(
            "Service spec '$name' won't deploy a container. Will use service running on 'localhost:${serviceConfig.port}' with env variables $envs")
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

  internal fun getEndpointUrl(config: RestateDeployerConfig): URL {
    val hostNamePort = toHostnamePort(config)
    return URL("http", hostNamePort.first, hostNamePort.second, "/")
  }
}
