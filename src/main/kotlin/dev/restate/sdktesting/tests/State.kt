// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import dev.restate.client.Client
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.InjectClient
import dev.restate.sdktesting.infra.RestateDeployerExtension
import dev.restate.sdktesting.infra.ServiceSpec
import java.util.*
import java.util.function.Function
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
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
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(
                  CounterMetadata.SERVICE_NAME,
                  ProxyMetadata.SERVICE_NAME,
                  MapObjectMetadata.SERVICE_NAME))
    }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun add(@InjectClient ingressClient: Client) = runTest {
    val counterId = UUID.randomUUID().toString()
    val counterClient = CounterClient.fromClient(ingressClient, counterId)
    val res1 = counterClient.add(1, idempotentCallOptions)
    assertThat(res1.oldValue).isEqualTo(0)
    assertThat(res1.newValue).isEqualTo(1)

    val res2 = counterClient.add(2, idempotentCallOptions)
    assertThat(res2.oldValue).isEqualTo(1)
    assertThat(res2.newValue).isEqualTo(3)
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun proxyOneWayAdd(@InjectClient ingressClient: Client) = runTest {
    val counterId = UUID.randomUUID().toString()
    val proxyClient = ProxyClient.fromClient(ingressClient)
    val counterClient = CounterClient.fromClient(ingressClient, counterId)

    for (x in 0.rangeUntil(3)) {
      proxyClient.oneWayCall(
          ProxyRequest(
              CounterMetadata.SERVICE_NAME,
              counterId,
              "add",
              Json.encodeToString(1).encodeToByteArray()),
          idempotentCallOptions)
    }

    await untilAsserted { assertThat(counterClient.get()).isEqualTo(3L) }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun listStateAndClearAll(@InjectClient ingressClient: Client) = runTest {
    val mapName = UUID.randomUUID().toString()
    val mapObj = MapObjectClient.fromClient(ingressClient, mapName)
    val anotherMapObj = MapObjectClient.fromClient(ingressClient, mapName + "1")

    mapObj.set(Entry("my-key-0", "my-value-0"), idempotentCallOptions)
    mapObj.set(Entry("my-key-1", "my-value-1"), idempotentCallOptions)

    // Set state to another map
    anotherMapObj.set(Entry("my-key-2", "my-value-2"), idempotentCallOptions)

    // Clear all
    assertThat(mapObj.clearAll(idempotentCallOptions))
        .map(Function { it.key })
        .containsExactlyInAnyOrder("my-key-0", "my-key-1")

    // Check keys are not available
    assertThat(mapObj.get("my-key-0", idempotentCallOptions)).isEmpty()
    assertThat(mapObj.get("my-key-1", idempotentCallOptions)).isEmpty()

    // Check the other service instance was left untouched
    assertThat(anotherMapObj.get("my-key-2", idempotentCallOptions)).isEqualTo("my-value-2")
  }
}
