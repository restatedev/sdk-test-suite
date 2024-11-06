// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import dev.restate.sdk.client.Client
import dev.restate.sdktesting.contracts.CounterClient
import dev.restate.sdktesting.contracts.CounterDefinitions
import dev.restate.sdktesting.infra.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("always-suspending")
@Tag("only-single-node")
class StopRuntime {

  companion object {
    @JvmStatic
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(ServiceSpec.defaultBuilder().withServices(CounterDefinitions.SERVICE_NAME))
    }
  }

  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @Test
  fun startAndStopRuntimeRetainsTheState(
      @InjectClient ingressClient: Client,
      @InjectContainerHandle(RESTATE_RUNTIME) runtimeHandle: ContainerHandle
  ) = runTest {
    var counterClient = CounterClient.fromClient(ingressClient, "my-key")

    val res1 = counterClient.add(1)
    assertThat(res1.oldValue).isEqualTo(0)
    assertThat(res1.newValue).isEqualTo(1)

    // Stop and start the runtime
    runtimeHandle.terminateAndRestart()

    // We need a new client, because on restarts docker might mess up the exposed ports. NotFunky
    // but true...
    counterClient =
        CounterClient.fromClient(
            Client.connect("http://127.0.0.1:${runtimeHandle.getMappedPort(8080)!!}"), "my-key")
    val res2 = counterClient.add(2)
    assertThat(res2.oldValue).isEqualTo(1)
    assertThat(res2.newValue).isEqualTo(3)
  }
}
