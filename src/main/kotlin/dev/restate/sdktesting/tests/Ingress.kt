// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import dev.restate.admin.api.ServiceApi
import dev.restate.admin.client.ApiClient
import dev.restate.admin.model.ModifyServiceRequest
import dev.restate.client.Client
import dev.restate.client.SendResponse.SendStatus
import dev.restate.client.kotlin.*
import dev.restate.common.Target
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.*
import java.net.URI
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

class Ingress {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(
                  CounterMetadata.SERVICE_NAME,
                  ProxyMetadata.SERVICE_NAME,
                  TestUtilsServiceMetadata.SERVICE_NAME,
                  VirtualObjectCommandInterpreterMetadata.SERVICE_NAME))
      // We need the short cleanup interval b/c of the tests with the idempotent invoke.
      withEnv("RESTATE_WORKER__CLEANUP_INTERVAL", "1s")
    }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @DisplayName("Idempotent invocation to a virtual object")
  fun idempotentInvokeVirtualObject(
      @InjectAdminURI adminURI: URI,
      @InjectClient ingressClient: Client
  ) = runTest {
    // Let's update the idempotency retention time to 3 seconds, to make this test faster
    val adminServiceClient = ServiceApi(ApiClient().setHost(adminURI.host).setPort(adminURI.port))
    adminServiceClient.modifyService(
        CounterMetadata.SERVICE_NAME, ModifyServiceRequest().idempotencyRetention("3s"))

    val counterRandomName = UUID.randomUUID().toString()
    val myIdempotencyId = UUID.randomUUID().toString()

    val counterClient = CounterClient.fromClient(ingressClient, counterRandomName)

    // First call updates the value
    val firstResponse = counterClient.add(2) { idempotencyKey = myIdempotencyId }
    assertThat(firstResponse)
        .returns(0, CounterUpdateResponse::oldValue)
        .returns(2, CounterUpdateResponse::newValue)

    // Next call returns the same value
    val secondResponse = counterClient.add(2) { idempotencyKey = myIdempotencyId }
    assertThat(secondResponse)
        .returns(0L, CounterUpdateResponse::oldValue)
        .returns(2L, CounterUpdateResponse::newValue)

    // Await until the idempotency id is cleaned up and the next idempotency call updates the
    // counter again
    await withAlias
        "cleanup of the previous idempotent request" untilAsserted
        {
          assertThat(counterClient.add(2) { idempotencyKey = myIdempotencyId })
              .returns(2, CounterUpdateResponse::oldValue)
              .returns(4, CounterUpdateResponse::newValue)
        }

    // State in the counter service is now equal to 4
    await withAlias
        "Get returns 4 now" untilAsserted
        {
          assertThat(counterClient.get()).isEqualTo(4L)
        }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @DisplayName("Idempotent invocation to a service")
  fun idempotentInvokeService(@InjectClient ingressClient: Client) = runTest {
    val counterRandomName = UUID.randomUUID().toString()
    val myIdempotencyId = UUID.randomUUID().toString()

    val counterClient = CounterClient.fromClient(ingressClient, counterRandomName)
    val proxyCounterClient = ProxyClient.fromClient(ingressClient)

    // Send request twice
    proxyCounterClient.oneWayCall(
        ProxyRequest(
            CounterMetadata.SERVICE_NAME,
            counterRandomName,
            "add",
            Json.encodeToString(2).encodeToByteArray())) {
          idempotencyKey = myIdempotencyId
        }
    proxyCounterClient.oneWayCall(
        ProxyRequest(
            CounterMetadata.SERVICE_NAME,
            counterRandomName,
            "add",
            Json.encodeToString(2).encodeToByteArray())) {
          idempotencyKey = myIdempotencyId
        }

    // Wait for get
    await untilAsserted { assertThat(counterClient.get()).isEqualTo(2) }

    // Without request options this should be executed immediately and return 4
    assertThat(counterClient.add(2, idempotentCallOptions))
        .returns(2, CounterUpdateResponse::oldValue)
        .returns(4, CounterUpdateResponse::newValue)
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @DisplayName("Idempotent invocation to a virtual object using send")
  fun idempotentInvokeSend(@InjectClient ingressClient: Client) = runTest {
    val counterRandomName = UUID.randomUUID().toString()
    val myIdempotencyId = UUID.randomUUID().toString()

    val counterClient = CounterClient.fromClient(ingressClient, counterRandomName)

    // Send request twice
    val firstInvocationSendStatus = counterClient.send().add(2) { idempotencyKey = myIdempotencyId }
    assertThat(firstInvocationSendStatus.status).isEqualTo(SendStatus.ACCEPTED)
    val secondInvocationSendStatus =
        counterClient.send().add(2) { idempotencyKey = myIdempotencyId }
    assertThat(secondInvocationSendStatus.status).isEqualTo(SendStatus.PREVIOUSLY_ACCEPTED)

    // IDs should be the same
    assertThat(firstInvocationSendStatus.invocationHandle.invocationId())
        .startsWith("inv")
        .isEqualTo(secondInvocationSendStatus.invocationHandle.invocationId())

    // Wait for get
    await untilAsserted { assertThat(counterClient.get()).isEqualTo(2) }

    // Without request options this should be executed immediately and return 4
    assertThat(counterClient.add(2, idempotentCallOptions))
        .returns(2, CounterUpdateResponse::oldValue)
        .returns(4, CounterUpdateResponse::newValue)
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @DisplayName("Idempotent send then attach/getOutput")
  @Disabled
  fun idempotentSendThenAttach(@InjectClient ingressClient: Client) = runTest {
    val awakeableKey = UUID.randomUUID().toString()
    val myIdempotencyId = UUID.randomUUID().toString()
    val response = "response"

    // Send request
    val interpreter =
        VirtualObjectCommandInterpreterClient.fromClient(
            ingressClient, UUID.randomUUID().toString())
    val invocationId =
        interpreter
            .send()
            .interpretCommands(
                VirtualObjectCommandInterpreter.InterpretRequest.awaitAwakeable(awakeableKey)) {
                  idempotencyKey = myIdempotencyId
                }
            .invocationHandle
            .invocationId()
    val invocationHandle =
        ingressClient.invocationHandle(
            invocationId, VirtualObjectCommandInterpreterMetadata.Serde.INTERPRETCOMMANDS_OUTPUT)

    // Attach to request
    val blockedFut = invocationHandle.attachAsync()

    // Output is not ready yet
    assertThat(invocationHandle.getOutputSuspend().response.isReady).isFalse()

    // Blocked fut should still be blocked
    assertThat(blockedFut).isNotDone

    // Unblock
    await untilAsserted { assertThat(interpreter.hasAwakeable(awakeableKey)).isTrue }
    interpreter.resolveAwakeable(
        VirtualObjectCommandInterpreter.ResolveAwakeable(awakeableKey, response),
        idempotentCallOptions)

    // Attach should be completed
    assertThat(blockedFut.get()).isEqualTo(response)

    // Invoke get output
    assertThat(invocationHandle.getOutputSuspend().response.value).isEqualTo(response)
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @DisplayName("Idempotent send then attach/getOutput with idempotency key")
  fun idempotentSendThenAttachWIthIdempotencyKey(@InjectClient ingressClient: Client) = runTest {
    val awakeableKey = UUID.randomUUID().toString()
    val myIdempotencyId = UUID.randomUUID().toString()
    val response = "response"
    val interpreterId = UUID.randomUUID().toString()

    // Send request
    val interpreter = VirtualObjectCommandInterpreterClient.fromClient(ingressClient, interpreterId)
    assertThat(
            interpreter
                .send()
                .interpretCommands(
                    VirtualObjectCommandInterpreter.InterpretRequest.awaitAwakeable(awakeableKey)) {
                      idempotencyKey = myIdempotencyId
                    }
                .status)
        .isEqualTo(SendStatus.ACCEPTED)

    val invocationHandle =
        ingressClient.idempotentInvocationHandle(
            Target.virtualObject(
                VirtualObjectCommandInterpreterMetadata.SERVICE_NAME,
                interpreterId,
                "interpretCommands"),
            myIdempotencyId,
            VirtualObjectCommandInterpreterMetadata.Serde.INTERPRETCOMMANDS_OUTPUT)

    // Attach to request
    val blockedFut = invocationHandle.attachAsync()

    // Output is not ready yet
    assertThat(invocationHandle.getOutputSuspend().response.isReady).isFalse()

    // Blocked fut should still be blocked
    assertThat(blockedFut).isNotDone

    // Unblock
    await withAlias
        "sync point" untilAsserted
        {
          assertThat(interpreter.hasAwakeable(awakeableKey)).isTrue
        }
    interpreter.resolveAwakeable(
        VirtualObjectCommandInterpreter.ResolveAwakeable(awakeableKey, response),
        idempotentCallOptions)

    // Attach should be completed
    assertThat(blockedFut.get().response).isEqualTo(response)

    // Invoke get output
    assertThat(invocationHandle.getOutputSuspend().response().value).isEqualTo(response)
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  fun headersPassThrough(@InjectClient ingressClient: Client) = runTest {
    val headerName = "x-my-custom-header"
    val headerValue = "x-my-custom-value"

    assertThat(
            TestUtilsServiceClient.fromClient(ingressClient).echoHeaders {
              idempotencyKey = UUID.randomUUID().toString()
              headers = mapOf(headerName to headerValue)
            })
        .containsEntry(headerName, headerValue)
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @DisplayName("Raw input and raw output")
  fun rawHandler(@InjectClient ingressClient: Client) = runTest {
    val bytes = Random.nextBytes(100)

    assertThat(
            TestUtilsServiceClient.fromClient(ingressClient).rawEcho(bytes, idempotentCallOptions))
        .isEqualTo(bytes)
  }
}
