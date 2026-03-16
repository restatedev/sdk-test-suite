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
import dev.restate.client.kotlin.*
import dev.restate.common.Target
import dev.restate.common.reflections.ReflectionUtils.extractServiceName
import dev.restate.sdktesting.contracts.Counter
import dev.restate.sdktesting.contracts.Counter.CounterUpdateResponse
import dev.restate.sdktesting.contracts.Proxy
import dev.restate.sdktesting.contracts.TestUtilsService
import dev.restate.sdktesting.infra.InjectClient
import dev.restate.sdktesting.infra.RestateDeployerExtension
import dev.restate.sdktesting.infra.ServiceSpec
import dev.restate.serde.TypeTag
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Tag("always-suspending")
class ServiceToServiceCommunication {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(Proxy::class, TestUtilsService::class, Counter::class))
    }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun regularCall(@InjectClient ingressClient: Client) = runTest {
    val proxyClient = ingressClient.toService<Proxy>()

    // Send request twice
    assertThat(
            proxyClient
                .request {
                  call(
                      Proxy.ProxyRequest(
                          extractServiceName(TestUtilsService::class.java),
                          null,
                          "uppercaseEcho",
                          Json.encodeToString("ping").encodeToByteArray()))
                }
                .options(idempotentCallOptions)
                .call()
                .response)
        .isEqualTo(Json.encodeToString("PING").encodeToByteArray())
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun oneWayCall(@InjectClient ingressClient: Client) = runTest {
    val counterId = UUID.randomUUID().toString()
    val proxyClient = ingressClient.toService<Proxy>()
    val counterClient = ingressClient.toVirtualObject<Counter>(counterId)

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

    await untilAsserted
        {
          assertThat(counterClient.request { get() }.call().response).isEqualTo(1L)
        }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun oneWayCallWithIdempotencyKey(@InjectClient ingressClient: Client) = runTest {
    val counterId = UUID.randomUUID().toString()
    val idempotencyKey = UUID.randomUUID().toString()
    val proxyClient = ingressClient.toService<Proxy>()
    val counterClient = ingressClient.toVirtualObject<Counter>(counterId)

    // We do this in a loop, because there can be failures
    await untilAsserted
        {
          proxyClient
              .request {
                oneWayCall(
                    Proxy.ProxyRequest(
                        extractServiceName(Counter::class.java),
                        counterId,
                        "add",
                        Json.encodeToString(1).encodeToByteArray(),
                        idempotencyKey = idempotencyKey))
              }
              .call()
        }

    await untilAsserted
        {
          assertThat(counterClient.request { get() }.call().response).isEqualTo(1L)
        }

    assertThat(
            ingressClient
                .idempotentInvocationHandle(
                    Target.virtualObject(extractServiceName(Counter::class.java), counterId, "add"),
                    idempotencyKey,
                    TypeTag.of(CounterUpdateResponse::class.java))
                .getOutputSuspend()
                .response
                .value)
        .isEqualTo(CounterUpdateResponse(0, 1))
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun callWithIdempotencyKey(@InjectClient ingressClient: Client) = runTest {
    val counterId = UUID.randomUUID().toString()
    val idempotencyKey = UUID.randomUUID().toString()
    val proxyClient = ingressClient.toService<Proxy>()
    val counterClient = ingressClient.toVirtualObject<Counter>(counterId)

    // We do this in a loop, because there can be failures
    await untilAsserted
        {
          val rawResult =
              proxyClient
                  .request {
                    call(
                        Proxy.ProxyRequest(
                            extractServiceName(Counter::class.java),
                            counterId,
                            "add",
                            Json.encodeToString(1).encodeToByteArray(),
                            idempotencyKey = idempotencyKey))
                  }
                  .call()
                  .response

          val jsonResult = Json.decodeFromString<CounterUpdateResponse>(rawResult.decodeToString())

          assertThat(jsonResult).isEqualTo(CounterUpdateResponse(0, 1))
        }

    await untilAsserted
        {
          assertThat(counterClient.request { get() }.call().response).isEqualTo(1L)
        }

    assertThat(
            ingressClient
                .idempotentInvocationHandle<CounterUpdateResponse>(
                    Target.virtualObject(extractServiceName(Counter::class.java), counterId, "add"),
                    idempotencyKey)
                .getOutputSuspend()
                .response
                .value)
        .isEqualTo(CounterUpdateResponse(0, 1))
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  @Tag("timers")
  fun oneWayCallWithDelay(@InjectClient ingressClient: Client) =
      runTest(timeout = 30.seconds) {
        val counterId = UUID.randomUUID().toString()
        val proxyClient = ingressClient.toService<Proxy>()
        val counterClient = ingressClient.toVirtualObject<Counter>(counterId)

        val elapsed = measureNanoTime {
          for (i in 1..10) {
            proxyClient
                .request {
                  oneWayCall(
                      Proxy.ProxyRequest(
                          extractServiceName(Counter::class.java),
                          counterId,
                          "add",
                          Json.encodeToString(1).encodeToByteArray(),
                          // This is a reasonably long time to avoid that the timeToAssert
                          // generates too many false positives
                          delayMillis = 5000))
                }
                .options(idempotentCallOptions)
                .call()
          }
          await untilAsserted
              {
                assertThat(counterClient.request { get() }.call().response).isEqualTo(10L)
              }
        }

        // This assert is checking two things:
        // * That those proxied calls happened.
        //    This can, of course, generate false positives if the creation of one way calls and the
        // assert took more than 5 seconds
        // * That the delay timer is respected BEFORE the queueing of the invocation,
        //    otherwise this whole test should have taken at least 50 seconds, while the test
        // timeout is 30 seconds.
        assertThat(elapsed.nanoseconds).isGreaterThanOrEqualTo(5.seconds)
      }
}
