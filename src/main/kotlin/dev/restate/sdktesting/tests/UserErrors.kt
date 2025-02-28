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
import dev.restate.sdktesting.contracts.CounterClient
import dev.restate.sdktesting.contracts.CounterMetadata
import dev.restate.sdktesting.contracts.FailingClient
import dev.restate.sdktesting.contracts.FailingMetadata
import dev.restate.sdktesting.infra.*
import java.util.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

private const val SUCCESS_ATTEMPT = 4

@Tag("always-suspending")
class UserErrors {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(FailingMetadata.SERVICE_NAME, CounterMetadata.SERVICE_NAME))
    }
  }

  @DisplayName("Test calling method that fails terminally")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun invokeTerminallyFailingCall(@InjectClient ingressClient: Client) = runTest {
    val errorMessage = "my error"

    assertThat(
            runCatching {
                  FailingClient.fromClient(ingressClient, UUID.randomUUID().toString())
                      .terminallyFailingCall(errorMessage, idempotentCallOptions)
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

  @DisplayName("Test set then fail should persist the set")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun setStateThenFailShouldPersistState(@InjectClient ingressClient: Client) = runTest {
    val counterName = "my-failure-counter"
    val counterClient = CounterClient.fromClient(ingressClient, counterName)

    assertThat(
            runCatching { counterClient.addThenFail(1, idempotentCallOptions) }.exceptionOrNull())
        .hasMessageContaining(counterName)

    assertThat(counterClient.get(idempotentCallOptions)).isEqualTo(1)
  }

  @DisplayName("Test propagate failure from another service")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun internalCallFailurePropagation(@InjectClient ingressClient: Client) = runTest {
    val errorMessage = "propagated error"
    val failingClient = FailingClient.fromClient(ingressClient, UUID.randomUUID().toString())

    assertThat(
            runCatching {
                  failingClient.callTerminallyFailingCall(errorMessage, idempotentCallOptions)
                }
                .exceptionOrNull())
        .hasMessageContaining(errorMessage)
  }

  @DisplayName("Test invocations are retried until they succeed")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun invocationWithEventualSuccess(@InjectClient ingressClient: Client) = runTest {
    assertThat(
            FailingClient.fromClient(ingressClient, UUID.randomUUID().toString())
                .failingCallWithEventualSuccess(idempotentCallOptions))
        .isEqualTo(SUCCESS_ATTEMPT)
  }

  @DisplayName("Test terminal error of side effects is propagated")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun sideEffectWithTerminalError(@InjectClient ingressClient: Client) = runTest {
    val errorMessage = "failed side effect"

    assertThat(
            runCatching {
                  FailingClient.fromClient(ingressClient, UUID.randomUUID().toString())
                      .terminallyFailingSideEffect(errorMessage, idempotentCallOptions)
                }
                .exceptionOrNull())
        .hasMessageContaining(errorMessage)
  }
}
