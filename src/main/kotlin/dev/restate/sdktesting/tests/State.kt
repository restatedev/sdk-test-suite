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
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.InjectClient
import dev.restate.sdktesting.infra.RestateDeployerExtension
import dev.restate.sdktesting.infra.ServiceSpec
import java.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Tag("always-suspending")
@Tag("lazy-state")
class State {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(ServiceSpec.DEFAULT)
    }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun add(@InjectClient ingressClient: Client) = runTest {
    CounterClient.fromClient(ingressClient, "noReturnValue").add(1)
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun getAndSet(@InjectClient ingressClient: Client) = runTest {
    val counterClient = CounterClient.fromClient(ingressClient, "getAndSet")
    val res1 = counterClient.getAndAdd(1)
    assertThat(res1.oldValue).isEqualTo(0)
    assertThat(res1.newValue).isEqualTo(1)

    val res2 = counterClient.getAndAdd(2)
    assertThat(res2.oldValue).isEqualTo(1)
    assertThat(res2.newValue).isEqualTo(3)
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun setStateViaOneWayCallFromAnotherService(@InjectClient ingressClient: Client) = runTest {
    val counterName = "setStateViaOneWayCallFromAnotherService"
    val proxyCounter = ProxyCounterClient.fromClient(ingressClient)

    proxyCounter.addInBackground(AddRequest(counterName, 1))
    proxyCounter.addInBackground(AddRequest(counterName, 1))
    proxyCounter.addInBackground(AddRequest(counterName, 1))

    await untilCallTo
        {
          runBlocking { CounterClient.fromClient(ingressClient, counterName).get() }
        } matches
        { num ->
          num!! == 3L
        }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun listStateAndClearAll(@InjectClient ingressClient: Client) = runTest {
    val mapName = UUID.randomUUID().toString()
    val mapObj = MapObjectClient.fromClient(ingressClient, mapName)
    val anotherMapObj = MapObjectClient.fromClient(ingressClient, mapName + "1")

    mapObj.set(Entry("my-key-0", "my-value-0"))
    mapObj.set(Entry("my-key-1", "my-value-1"))

    // Set state to another map
    anotherMapObj.set(Entry("my-key-2", "my-value-2"))

    // Clear all
    assertThat(mapObj.clearAll()).containsExactlyInAnyOrder("my-key-0", "my-key-1")

    // Check keys are not available
    assertThat(mapObj.get("my-key-0")).isEmpty()
    assertThat(mapObj.get("my-key-1")).isEmpty()

    // Check the other service instance was left untouched
    assertThat(anotherMapObj.get("my-key-2")).isEqualTo("my-value-2")
  }
}
