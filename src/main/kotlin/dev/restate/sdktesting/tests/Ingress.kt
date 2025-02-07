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
import dev.restate.sdk.client.CallRequestOptions
import dev.restate.sdk.client.Client
import dev.restate.sdk.client.SendResponse.SendStatus
import dev.restate.sdk.common.Target
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.*
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
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
                  AwakeableHolderDefinitions.SERVICE_NAME,
                  CounterDefinitions.SERVICE_NAME,
                  ProxyDefinitions.SERVICE_NAME,
                  TestUtilsServiceDefinitions.SERVICE_NAME))
      // We need the short cleanup interval b/c of the tests with the idempotent invoke.
      withEnv("RESTATE_WORKER__CLEANUP_INTERVAL", "1s")
    }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @DisplayName("Idempotent invocation to a virtual object")
  fun idempotentInvokeVirtualObject(
      @InjectMetaURL metaURL: URL,
      @InjectClient ingressClient: Client
  ) = runTest {
    // Let's update the idempotency retention time to 3 seconds, to make this test faster
    val adminServiceClient = ServiceApi(ApiClient().setHost(metaURL.host).setPort(metaURL.port))
    adminServiceClient.modifyService(
        CounterDefinitions.SERVICE_NAME, ModifyServiceRequest().idempotencyRetention("3s"))

    val counterRandomName = UUID.randomUUID().toString()
    val myIdempotencyId = UUID.randomUUID().toString()
    val requestOptions = CallRequestOptions().withIdempotency(myIdempotencyId)

    val counterClient = CounterClient.fromClient(ingressClient, counterRandomName)

    // First call updates the value
    val firstResponse = counterClient.add(2, requestOptions)
    assertThat(firstResponse)
        .returns(0, CounterUpdateResponse::oldValue)
        .returns(2, CounterUpdateResponse::newValue)

    // Next call returns the same value
    val secondResponse = counterClient.add(2, requestOptions)
    assertThat(secondResponse)
        .returns(0L, CounterUpdateResponse::oldValue)
        .returns(2L, CounterUpdateResponse::newValue)

    // Await until the idempotency id is cleaned up and the next idempotency call updates the
    // counter again
    await withAlias
        "cleanup of the previous idempotent request" untilAsserted
        {
          assertThat(counterClient.add(2, requestOptions))
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
    val requestOptions = CallRequestOptions().withIdempotency(myIdempotencyId)

    val counterClient = CounterClient.fromClient(ingressClient, counterRandomName)
    val proxyCounterClient = ProxyClient.fromClient(ingressClient)

    // Send request twice
    proxyCounterClient.oneWayCall(
        ProxyRequest(
            CounterDefinitions.SERVICE_NAME,
            counterRandomName,
            "add",
            Json.encodeToString(2).encodeToByteArray()),
        requestOptions)
    proxyCounterClient.oneWayCall(
        ProxyRequest(
            CounterDefinitions.SERVICE_NAME,
            counterRandomName,
            "add",
            Json.encodeToString(2).encodeToByteArray()),
        requestOptions)

    // Wait for get
    await untilAsserted { assertThat(counterClient.get()).isEqualTo(2) }

    // Without request options this should be executed immediately and return 4
    assertThat(counterClient.add(2, idempotentCallOptions()))
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
    val requestOptions = CallRequestOptions().withIdempotency(myIdempotencyId)

    val counterClient = CounterClient.fromClient(ingressClient, counterRandomName)

    // Send request twice
    val firstInvocationSendStatus = counterClient.send().add(2, requestOptions)
    assertThat(firstInvocationSendStatus.status).isEqualTo(SendStatus.ACCEPTED)
    val secondInvocationSendStatus = counterClient.send().add(2, requestOptions)
    assertThat(secondInvocationSendStatus.status).isEqualTo(SendStatus.PREVIOUSLY_ACCEPTED)

    // IDs should be the same
    assertThat(firstInvocationSendStatus.invocationId)
        .startsWith("inv")
        .isEqualTo(secondInvocationSendStatus.invocationId)

    // Wait for get
    await untilAsserted { assertThat(counterClient.get()).isEqualTo(2) }

    // Without request options this should be executed immediately and return 4
    assertThat(counterClient.add(2, idempotentCallOptions()))
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
    val testUtilsClient = TestUtilsServiceClient.fromClient(ingressClient)
    val invocationId =
        testUtilsClient
            .send()
            .createAwakeableAndAwaitIt(
                CreateAwakeableAndAwaitItRequest(awakeableKey),
                CallRequestOptions().withIdempotency(myIdempotencyId))
            .invocationId
    val invocationHandle =
        ingressClient.invocationHandle(
            invocationId, TestUtilsServiceDefinitions.Serde.CREATEAWAKEABLEANDAWAITIT_OUTPUT)

    // Attach to request
    val blockedFut = invocationHandle.attachAsync()

    // Output is not ready yet
    assertThat(invocationHandle.output.isReady).isFalse()

    // Blocked fut should still be blocked
    assertThat(blockedFut).isNotDone

    // Unblock
    val awakeableHolderClient = AwakeableHolderClient.fromClient(ingressClient, awakeableKey)
    await untilAsserted { assertThat(awakeableHolderClient.hasAwakeable()).isTrue }
    awakeableHolderClient.unlock(response, idempotentCallOptions())

    // Attach should be completed
    assertThat(blockedFut.get()).isEqualTo(AwakeableResultResponse(response))

    // Invoke get output
    assertThat(invocationHandle.output.value).isEqualTo(AwakeableResultResponse(response))
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @DisplayName("Idempotent send then attach/getOutput with idempotency key")
  fun idempotentSendThenAttachWIthIdempotencyKey(@InjectClient ingressClient: Client) = runTest {
    val awakeableKey = UUID.randomUUID().toString()
    val myIdempotencyId = UUID.randomUUID().toString()
    val response = "response"

    // Send request
    val testUtilsClient = TestUtilsServiceClient.fromClient(ingressClient)
    assertThat(
            testUtilsClient
                .send()
                .createAwakeableAndAwaitIt(
                    CreateAwakeableAndAwaitItRequest(awakeableKey),
                    CallRequestOptions().withIdempotency(myIdempotencyId))
                .status)
        .isEqualTo(SendStatus.ACCEPTED)

    val invocationHandle =
        ingressClient.idempotentInvocationHandle(
            Target.service(TestUtilsServiceDefinitions.SERVICE_NAME, "createAwakeableAndAwaitIt"),
            myIdempotencyId,
            TestUtilsServiceDefinitions.Serde.CREATEAWAKEABLEANDAWAITIT_OUTPUT)

    // Attach to request
    val blockedFut = invocationHandle.attachAsync()

    // Output is not ready yet
    assertThat(invocationHandle.output.isReady).isFalse()

    // Blocked fut should still be blocked
    assertThat(blockedFut).isNotDone

    // Unblock
    val awakeableHolderClient = AwakeableHolderClient.fromClient(ingressClient, awakeableKey)
    await untilAsserted { assertThat(awakeableHolderClient.hasAwakeable()).isTrue }
    awakeableHolderClient.unlock(response, idempotentCallOptions())

    // Attach should be completed
    assertThat(blockedFut.get()).isEqualTo(AwakeableResultResponse(response))

    // Invoke get output
    assertThat(invocationHandle.output.value).isEqualTo(AwakeableResultResponse(response))
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  fun headersPassThrough(@InjectClient ingressClient: Client) = runTest {
    val headerName = "x-my-custom-header"
    val headerValue = "x-my-custom-value"

    assertThat(
            TestUtilsServiceClient.fromClient(ingressClient)
                .echoHeaders(idempotentCallOptions().withHeader(headerName, headerValue)))
        .containsEntry(headerName, headerValue)
  }
}
