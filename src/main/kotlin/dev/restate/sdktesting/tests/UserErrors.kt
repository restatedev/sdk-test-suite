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
import dev.restate.sdktesting.contracts.Counter
import dev.restate.sdktesting.contracts.Failing
import dev.restate.sdktesting.infra.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Tag("always-suspending")
class UserErrors {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(ServiceSpec.defaultBuilder().withServices(Failing::class, Counter::class))
    }

    private const val SUCCESS_ATTEMPT = 4

    private val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }

    private val TEST_METADATA = mapOf("key1" to "value1", "key2" to "value2")
  }

  @DisplayName("Test calling method that fails terminally")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun invokeTerminallyFailingCall(@InjectClient ingressClient: Client) = runTest {
    val errorMessage = "my error"

    assertThat(
            runCatching {
                  ingressClient
                      .toVirtualObject<Failing>(UUID.randomUUID().toString())
                      .request { terminallyFailingCall(Failing.FailureToPropagate(errorMessage)) }
                      .options(idempotentCallOptions)
                      .call()
                      .response
                }
                .exceptionOrNull())
        .hasMessageContaining(errorMessage)
  }

  @DisplayName("Test calling method that fails terminally multiple times")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun failSeveralTimes(@InjectClient ingressClient: Client) = runTest {
    // This test checks the endpoint doesn't become unstable after the first failure
    invokeTerminallyFailingCall(ingressClient)
    invokeTerminallyFailingCall(ingressClient)
    invokeTerminallyFailingCall(ingressClient)
  }

  @DisplayName("Test propagate failure from another service")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun internalCallFailurePropagation(@InjectClient ingressClient: Client) = runTest {
    val errorMessage = "propagated error"
    val failingClient = ingressClient.toVirtualObject<Failing>(UUID.randomUUID().toString())

    assertThat(
            runCatching {
                  failingClient
                      .request {
                        callTerminallyFailingCall(Failing.FailureToPropagate(errorMessage))
                      }
                      .options(idempotentCallOptions)
                      .call()
                      .response
                }
                .exceptionOrNull())
        .hasMessageContaining(errorMessage)
  }

  @DisplayName("Test terminal error of side effects is propagated")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun sideEffectWithTerminalError(@InjectClient ingressClient: Client) = runTest {
    val errorMessage = "failed side effect"

    assertThat(
            runCatching {
                  ingressClient
                      .toVirtualObject<Failing>(UUID.randomUUID().toString())
                      .request {
                        terminallyFailingSideEffect(Failing.FailureToPropagate(errorMessage))
                      }
                      .options(idempotentCallOptions)
                      .call()
                      .response
                }
                .exceptionOrNull())
        .hasMessageContaining(errorMessage)
  }

  @DisplayName("Test calling method that fails terminally with metadata")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun invokeTerminallyFailingCallWithMetadata(@InjectIngressURI ingressUri: URI) = runTest {
    val errorMessage = "my error with metadata"
    val key = UUID.randomUUID().toString()

    val errorBody =
        callIngressRawAndExpectError(
            ingressUri,
            key,
            "terminallyFailingCall",
            Failing.FailureToPropagate(errorMessage, TEST_METADATA))

    assertThat(errorBody.message).contains(errorMessage)
    assertThat(errorBody.metadata).containsAllEntriesOf(TEST_METADATA)
  }

  @DisplayName("Test calling method that fails terminally with metadata multiple times")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun failSeveralTimesWithMetadata(@InjectIngressURI ingressUri: URI) = runTest {
    invokeTerminallyFailingCallWithMetadata(ingressUri)
    invokeTerminallyFailingCallWithMetadata(ingressUri)
    invokeTerminallyFailingCallWithMetadata(ingressUri)
  }

  @DisplayName("Test propagate failure from another service with metadata")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun internalCallFailurePropagationWithMetadata(@InjectIngressURI ingressUri: URI) = runTest {
    val errorMessage = "propagated error with metadata"
    val key = UUID.randomUUID().toString()

    val errorBody =
        callIngressRawAndExpectError(
            ingressUri,
            key,
            "callTerminallyFailingCall",
            Failing.FailureToPropagate(errorMessage, TEST_METADATA))

    assertThat(errorBody.message).contains(errorMessage)
    assertThat(errorBody.metadata).containsAllEntriesOf(TEST_METADATA)
  }

  @DisplayName("Test terminal error of side effects is propagated with metadata")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun sideEffectWithTerminalErrorWithMetadata(@InjectIngressURI ingressUri: URI) = runTest {
    val errorMessage = "failed side effect with metadata"
    val key = UUID.randomUUID().toString()

    val errorBody =
        callIngressRawAndExpectError(
            ingressUri,
            key,
            "terminallyFailingSideEffect",
            Failing.FailureToPropagate(errorMessage, TEST_METADATA))

    assertThat(errorBody.message).contains(errorMessage)
    assertThat(errorBody.metadata).containsAllEntriesOf(TEST_METADATA)
  }

  @DisplayName("Test set then fail should persist the set")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun setStateThenFailShouldPersistState(@InjectClient ingressClient: Client) = runTest {
    val counterName = "my-failure-counter"
    val counterClient = ingressClient.toVirtualObject<Counter>(counterName)

    assertThat(
            runCatching {
                  counterClient.request { addThenFail(1) }.options(idempotentCallOptions).call()
                }
                .exceptionOrNull())
        .hasMessageContaining(counterName)

    assertThat(counterClient.request { get() }.options(idempotentCallOptions).call().response)
        .isEqualTo(1)
  }

  @DisplayName("Test invocations are retried until they succeed")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun invocationWithEventualSuccess(@InjectClient ingressClient: Client) = runTest {
    assertThat(
            ingressClient
                .toVirtualObject<Failing>(UUID.randomUUID().toString())
                .request { failingCallWithEventualSuccess() }
                .options(idempotentCallOptions)
                .call()
                .response)
        .isEqualTo(SUCCESS_ATTEMPT)
  }

  @Serializable
  private data class RestateError(
      val code: Int? = null,
      val message: String,
      val metadata: Map<String, String>? = null
  )

  private suspend fun callIngressRawAndExpectError(
      ingressUri: URI,
      key: String,
      handler: String,
      body: Failing.FailureToPropagate
  ): RestateError {
    val httpClient = HttpClient.newHttpClient()
    val requestBody = json.encodeToString(body)
    val idempotencyKey = UUID.randomUUID().toString()
    val request =
        HttpRequest.newBuilder()
            .uri(ingressUri.resolve("Failing/$key/$handler"))
            .header("Content-Type", "application/json")
            .header("idempotency-key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
    val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()

    assertThat(response.statusCode()).isGreaterThanOrEqualTo(400)
    return json.decodeFromString<RestateError>(response.body())
  }
}
