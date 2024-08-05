// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

import eu.rekawek.toxiproxy.ToxiproxyClient
import java.util.concurrent.TimeUnit
import java.util.stream.IntStream
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import org.rnorth.ducttape.ratelimits.RateLimiterBuilder
import org.testcontainers.containers.Network
import org.testcontainers.containers.ToxiproxyContainer
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy

@Deprecated("Remove this and replace with a socat container")
internal class ProxyContainer(private val container: ToxiproxyContainer) {

  companion object {
    // From https://www.testcontainers.org/modules/toxiproxy/
    private const val TOXIPROXY_CONTROL_PORT = 8474
    // From https://www.testcontainers.org/modules/toxiproxy/
    private const val PORT_OFFSET = 8666
    // For the time being, we use only 2 ports.
    private const val MAX_PORTS = 2
  }

  private var assignedPorts = 0
  private val portMappings = mutableMapOf<String, Int>()

  // -- Lifecycle

  internal fun start(network: Network, testReportDir: String) {
    // We override what ToxiproxyContainer sets as default
    val portsToExpose =
        IntStream.concat(
                IntStream.of(TOXIPROXY_CONTROL_PORT),
                IntStream.range(PORT_OFFSET, PORT_OFFSET + MAX_PORTS))
            .boxed()
            .toArray { arrayOfNulls<Int>(it) }

    container
        .withNetwork(network)
        .withLogConsumer(ContainerLogger(testReportDir, "toxiproxy"))
        .withExposedPorts(*portsToExpose)
        .withStartupAttempts(3)
        .start()
  }

  internal fun stop() {
    container.stop()
  }

  // -- Port mapping

  internal fun mapPort(hostName: String, port: Int): Int {
    check(assignedPorts < MAX_PORTS) { "Cannot assign more than $assignedPorts ports" }

    val portName = "${hostName}-${port}"
    val upstreamAddress = address(hostName, port)
    val chosenPort = PORT_OFFSET + assignedPorts
    assignedPorts++

    val client = ToxiproxyClient(container.host, container.controlPort)
    client.createProxy(portName, address("0.0.0.0", chosenPort), upstreamAddress)
    portMappings[upstreamAddress] = chosenPort

    return container.getMappedPort(chosenPort)
  }

  internal fun getMappedPort(hostName: String, port: Int): Int? {
    return portMappings[address(hostName, port)]?.let { container.getMappedPort(it) }
  }

  internal fun waitHttp(httpWaitStrategy: HttpWaitStrategy, hostName: String, port: Int) {
    val mappedPort =
        portMappings[address(hostName, port)]
            ?: throw IllegalArgumentException("Cannot wait on non existing port")
    httpWaitStrategy
        .forPort(mappedPort)
        .withRateLimiter(
            RateLimiterBuilder.newBuilder()
                .withRate(100, TimeUnit.MILLISECONDS)
                .withConstantThroughput()
                .build())
        .withStartupTimeout(20.seconds.toJavaDuration())
        .waitUntilReady(WaitOnSpecificPortsTarget(listOf(mappedPort), container))
  }

  private fun address(hostName: String, port: Int): String {
    return "${hostName}:${port}"
  }
}
