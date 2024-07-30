// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate e2e tests,
// which are released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/e2e/blob/main/LICENSE

package dev.restate.sdktesting.tests

import dev.restate.sdk.client.Client
import dev.restate.sdktesting.contracts.CounterClient
import dev.restate.sdktesting.contracts.FailingClient
import dev.restate.sdktesting.infra.*
import java.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

private const val SUCCESS_ATTEMPT = 4

@Tag("always-suspending")
class ErrorsTest {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(ServiceSpec.DEFAULT)
    }
  }

  //  @DisplayName("Test terminal error of failing side effect with finite retry policy is
  // propagated")
  //  @Test
  //  @Execution(ExecutionMode.CONCURRENT)
  //  fun failingSideEffectWithFiniteRetryPolicy(@InjectBlockingStub stub:
  // FailingServiceBlockingStub) {
  //    val errorMessage = "some error message"
  //
  //    assertThatThrownBy {
  //          stub.failingSideEffectWithFiniteRetryPolicy(
  //              ErrorMessage.newBuilder()
  //                  .setKey(UUID.randomUUID().toString())
  //                  .setErrorMessage(errorMessage)
  //                  .build())
  //        }
  //        .asInstanceOf(type(StatusRuntimeException::class.java))
  //        .extracting(StatusRuntimeException::getStatus)
  //        .extracting(Status::getDescription, InstanceOfAssertFactories.STRING)
  //        .contains("failing side effect action")
  //  }

  // @DisplayName("Test propagate failure from sideEffect and internal invoke")
  //  @Test
  //  @Execution(ExecutionMode.CONCURRENT)
  //  fun sideEffectFailurePropagation(@InjectClient ingressClient: Client) {
  //    assertThat(
  //            FailingClient.fromClient(ingressClient, UUID.randomUUID().toString())
  //                .invokeExternalAndHandleFailure())
  //        // We match on this regex because there might be additional parts of the string injected
  //        // by runtime/sdk in the error message strings
  //        .matches("begin.*external_call.*internal_call")
  //  }

  @DisplayName("Test calling method that fails terminally")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun invokeTerminallyFailingCall(@InjectClient ingressClient: Client) = runTest {
    val errorMessage = "my error"

    assertThatThrownBy {
          runBlocking {
            FailingClient.fromClient(ingressClient, UUID.randomUUID().toString())
                .terminallyFailingCall(errorMessage)
          }
        }
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

  @DisplayName("Test set then fail should persist the set")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun setStateThenFailShouldPersistState(@InjectClient ingressClient: Client) = runTest {
    val counterName = "my-failure-counter"
    val counterClient = CounterClient.fromClient(ingressClient, counterName)

    assertThatThrownBy { runBlocking { counterClient.addThenFail(1) } }
        .hasMessageContaining(counterName)

    assertThat(counterClient.get()).isEqualTo(1)
  }

  @DisplayName("Test propagate failure from another service")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun internalCallFailurePropagation(@InjectClient ingressClient: Client) = runTest {
    val errorMessage = "propagated error"
    val failingClient = FailingClient.fromClient(ingressClient, UUID.randomUUID().toString())

    assertThatThrownBy { runBlocking { failingClient.callTerminallyFailingCall(errorMessage) } }
        .hasMessageContaining(errorMessage)
  }

  @DisplayName("Test side effects are retried until they succeed")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun sideEffectWithEventualSuccess(@InjectClient ingressClient: Client) = runTest {
    assertThat(
            FailingClient.fromClient(ingressClient, UUID.randomUUID().toString())
                .failingCallWithEventualSuccess())
        .isEqualTo(SUCCESS_ATTEMPT)
  }

  @DisplayName("Test invocations are retried until they succeed")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun invocationWithEventualSuccess(@InjectClient ingressClient: Client) = runTest {
    assertThat(
            FailingClient.fromClient(ingressClient, UUID.randomUUID().toString())
                .failingCallWithEventualSuccess())
        .isEqualTo(SUCCESS_ATTEMPT)
  }

  @DisplayName("Test terminal error of side effects is propagated")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun sideEffectWithTerminalError(@InjectClient ingressClient: Client) {
    val errorMessage = "failed side effect"

    assertThatThrownBy {
          runBlocking {
            FailingClient.fromClient(ingressClient, UUID.randomUUID().toString())
                .terminallyFailingSideEffect(errorMessage)
          }
        }
        .hasMessageContaining(errorMessage)
  }
}
