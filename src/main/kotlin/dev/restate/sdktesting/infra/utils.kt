// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

import com.github.dockerjava.api.command.InspectContainerResponse
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget

internal open class NotCachedContainerInfo(private val delegate: WaitStrategyTarget) :
    WaitStrategyTarget by delegate {
  override fun getContainerInfo(): InspectContainerResponse {
    // Need to ask the delegate to avoid recursive call to getContainerInfo()
    val containerId = this.delegate.containerId
    return dockerClient.inspectContainerCmd(containerId).exec()
  }
}

internal class WaitOnSpecificPortsTarget(
    private val ports: List<Int>,
    delegate: WaitStrategyTarget
) : NotCachedContainerInfo(delegate) {
  override fun getExposedPorts(): MutableList<Int> {
    return ports.toMutableList()
  }
}
