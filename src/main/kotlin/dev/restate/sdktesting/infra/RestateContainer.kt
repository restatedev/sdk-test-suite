// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
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
