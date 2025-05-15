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
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.AwaitAny
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.AwaitAnySuccessful
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.AwaitAwakeableOrTimeout
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.CreateAwakeable
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.InterpretRequest
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.RejectAwakeable
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.ResolveAwakeable
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.RunThrowTerminalException
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.Sleep
import dev.restate.sdktesting.infra.*
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Tag("always-suspending")
class Combinators {
  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(VirtualObjectCommandInterpreterHandlers.Metadata.SERVICE_NAME))
    }
  }

  @Test
  @DisplayName("Test awakeable or timeout using Await any")
  @Execution(ExecutionMode.CONCURRENT)
  fun awakeableOrTimeoutUsingAwaitAny(@InjectClient ingressClient: Client) = runTest {
    val testId = UUID.randomUUID().toString()
    val timeout = Duration.ofMillis(100L)

    assertThat(
            VirtualObjectCommandInterpreterClient.fromClient(ingressClient, testId)
                .interpretCommands(
                    InterpretRequest(
                        listOf(
                            AwaitAny(listOf(CreateAwakeable("awk1"), Sleep(timeout.toMillis()))))),
                    idempotentCallOptions))
        .isEqualTo("sleep")
  }

  @Test
  @DisplayName("Test awakeable or timeout using AwaitAwakeableOrTimeout")
  @Execution(ExecutionMode.CONCURRENT)
  fun awakeableOrTimeoutUsingAwakeableTimeoutCommand(@InjectClient ingressClient: Client) =
      runTest {
        val testId = UUID.randomUUID().toString()
        val timeout = Duration.ofMillis(100L)

        assertThat(
                runCatching {
                      VirtualObjectCommandInterpreterClient.fromClient(ingressClient, testId)
                          .interpretCommands(
                              InterpretRequest(
                                  listOf(
                                      AwaitAwakeableOrTimeout(
                                          "should-timeout-awk", timeout.toMillis()))),
                              idempotentCallOptions)
                    }
                    .exceptionOrNull())
            .message()
            .contains("await-timeout")
      }

  @Test
  @DisplayName("Test the first successful awakeable should be returned")
  @Execution(ExecutionMode.CONCURRENT)
  fun firstSuccessfulCompletedAwakeable(@InjectClient ingressClient: Client) = runTest {
    val testId = UUID.randomUUID().toString()
    val interpreterClient = VirtualObjectCommandInterpreterClient.fromClient(ingressClient, testId)
    val awk0 = "awk0"
    val awk1 = "awk1"
    val awk2 = "awk2"

    val result = async {
      interpreterClient.interpretCommands(
          InterpretRequest(
              listOf(
                  AwaitAnySuccessful(
                      listOf(
                          CreateAwakeable(awk0),
                          RunThrowTerminalException("run0"),
                          CreateAwakeable(awk1),
                          RunThrowTerminalException("run1"),
                          CreateAwakeable(awk2),
                          RunThrowTerminalException("run2"),
                      )))),
          idempotentCallOptions)
    }

    await withAlias
        "awakeable $awk2 created" untilAsserted
        {
          assertThat(interpreterClient.hasAwakeable(awk2)).isTrue()
        }

    // hasAwakeable might have to be retried in case of leadership changes
    assertThat(interpreterClient.hasAwakeable(awk0, idempotentCallOptions)).isTrue()
    // hasAwakeable might have to be retried in case of leadership changes
    assertThat(interpreterClient.hasAwakeable(awk1, idempotentCallOptions)).isTrue()

    // Now let's reject awakeable 2, this should not complete anything
    interpreterClient.rejectAwakeable(RejectAwakeable(awk2, "fail"), idempotentCallOptions)

    // Resolve awakeable 1, this will complete successfully
    interpreterClient.resolveAwakeable(ResolveAwakeable(awk1, "awk1-result"), idempotentCallOptions)

    assertThat(result.await()).isEqualTo("awk1-result")
  }
}
