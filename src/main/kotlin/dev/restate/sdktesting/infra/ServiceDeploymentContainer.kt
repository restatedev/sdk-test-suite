// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

import dev.restate.sdktesting.infra.RestateDeployer.Companion.RESTATE_URI_ENV
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.utility.DockerImageName

class ServiceDeploymentContainer(
    dockerImageName: DockerImageName,
    private val hostname: String,
    network: Network,
    restateURI: String,
    envs: Map<String, String>
) : GenericContainer<ServiceDeploymentContainer>(dockerImageName) {

  init {
    withEnv("PORT", "9080")
    withEnv(envs)
    networkAliases = ArrayList()
    withNetwork(network)
    withNetworkAliases(hostname)
    withEnv(RESTATE_URI_ENV, restateURI)
    withStartupAttempts(3)
  }

  fun configureLogger(testReportDir: String): ServiceDeploymentContainer {
    this.withLogConsumer(ContainerLogger(testReportDir, hostname))
    return this
  }
}
