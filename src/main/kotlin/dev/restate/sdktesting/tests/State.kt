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
import dev.restate.client.kotlin.response
import dev.restate.client.kotlin.toService
import dev.restate.client.kotlin.toVirtualObject
import dev.restate.common.reflections.ReflectionUtils.extractServiceName
import dev.restate.sdktesting.contracts.Counter
import dev.restate.sdktesting.contracts.MapObject
import dev.restate.sdktesting.contracts.MapObject.Entry
import dev.restate.sdktesting.contracts.Proxy
import dev.restate.sdktesting.infra.InjectClient
import dev.restate.sdktesting.infra.RestateDeployerExtension
import dev.restate.sdktesting.infra.ServiceSpec
import java.util.*
import java.util.function.Function
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
          ServiceSpec.defaultBuilder().withServices(Counter::class, Proxy::class, MapObject::class))
    }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun add(@InjectClient ingressClient: Client) = runTest {
    val counterId = UUID.randomUUID().toString()
    val counterClient = ingressClient.toVirtualObject<Counter>(counterId)
    val res1 = counterClient.request { add(1) }.options(idempotentCallOptions).call().response
    assertThat(res1.oldValue).isEqualTo(0)
    assertThat(res1.newValue).isEqualTo(1)

    val res2 = counterClient.request { add(2) }.options(idempotentCallOptions).call().response
    assertThat(res2.oldValue).isEqualTo(1)
    assertThat(res2.newValue).isEqualTo(3)
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun proxyOneWayAdd(@InjectClient ingressClient: Client) = runTest {
    val counterId = UUID.randomUUID().toString()
    val proxyClient = ingressClient.toService<Proxy>()
    val counterClient = ingressClient.toVirtualObject<Counter>(counterId)

    for (x in 0.rangeUntil(3)) {
      proxyClient
          .request {
            oneWayCall(
                Proxy.ProxyRequest(
                    extractServiceName(Counter::class.java),
                    counterId,
                    "add",
                    Json.encodeToString(1).encodeToByteArray()))
          }
          .options(idempotentCallOptions)
          .call()
    }

    await untilAsserted
        {
          assertThat(counterClient.request { get() }.call().response).isEqualTo(3L)
        }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun listStateAndClearAll(@InjectClient ingressClient: Client) = runTest {
    val mapName = UUID.randomUUID().toString()
    val mapObj = ingressClient.toVirtualObject<MapObject>(mapName)
    val anotherMapObj = ingressClient.toVirtualObject<MapObject>(mapName + "1")

    mapObj.request { set(Entry("my-key-0", "my-value-0")) }.options(idempotentCallOptions).call()
    mapObj.request { set(Entry("my-key-1", "my-value-1")) }.options(idempotentCallOptions).call()

    // Set state to another map
    anotherMapObj
        .request { set(Entry("my-key-2", "my-value-2")) }
        .options(idempotentCallOptions)
        .call()

    // Clear all
    assertThat(mapObj.request { clearAll() }.options(idempotentCallOptions).call().response)
        .map(Function { it.key })
        .containsExactlyInAnyOrder("my-key-0", "my-key-1")

    // Check keys are not available
    assertThat(mapObj.request { get("my-key-0") }.options(idempotentCallOptions).call().response)
        .isEmpty()
    assertThat(mapObj.request { get("my-key-1") }.options(idempotentCallOptions).call().response)
        .isEmpty()

    // Check the other service instance was left untouched
    assertThat(
            anotherMapObj
                .request { get("my-key-2") }
                .options(idempotentCallOptions)
                .call()
                .response)
        .isEqualTo("my-value-2")
  }
}
