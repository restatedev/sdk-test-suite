// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

import org.apache.logging.log4j.LogManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.utility.DockerImageName

class SocatContainer(listenPort: Int, targetHost: String, targetPort: Int, exposeOnHost: Boolean) :
    GenericContainer<RestateContainer>(DockerImageName.parse("docker.io/alpine/socat")) {

  companion object {
    private val LOG = LogManager.getLogger(SocatContainer::class.java)
  }

  init {
    val networkAlias = "socat-$targetHost-$targetPort"

    this.network = network
    this.networkAliases = arrayListOf(networkAlias)
    withStartupAttempts(3)

    if (exposeOnHost) {
      LOG.info("Going to expose {}:{} on localhost:{}", networkAlias, listenPort, listenPort)
      super.addFixedExposedPort(listenPort, listenPort)
    }

    withCommand("tcp-listen:$listenPort,fork,reuseaddr tcp-connect:$targetHost:$targetPort")
  }

  internal fun start(network: Network, testReportDir: String) {
    this.withNetwork(network)
        .withLogConsumer(ContainerLogger(testReportDir, this.networkAliases[0]!!))
        .start()
  }
}
