package dev.restate.sdktesting.infra

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

class RestateContainer(config: RestateDeployerConfig) :
    GenericContainer<RestateContainer>(DockerImageName.parse(config.restateContainerImage)) {
  init {
    if (config.localAdminPort != null) {
      super.addFixedExposedPort(config.localAdminPort, RUNTIME_META_ENDPOINT_PORT)
    }
    if (config.localIngressPort != null) {
      super.addFixedExposedPort(config.localIngressPort, RUNTIME_INGRESS_ENDPOINT_PORT)
    }

    // TODO move here good part of restate container config
  }
}
