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
import dev.restate.common.Request
import dev.restate.common.Target
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.*
import dev.restate.serde.Serde
import org.assertj.core.api.Assertions.*
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Test non-determinism/journal mismatch checks in the SDKs. */
@Tag("only-always-suspending")
@Tag("only-single-node")
class NonDeterminismErrors {
  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withEnv("RESTATE_DEFAULT_RETRY_POLICY__MAX_ATTEMPTS", "1")
      withEnv("RESTATE_DEFAULT_RETRY_POLICY__ON_MAX_ATTEMPTS", "kill")
      withEnv("RESTATE_DEFAULT_RETRY_POLICY__INITIAL_INTERVAL", "1ms")
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(
                  NonDeterministicHandlers.Metadata.SERVICE_NAME,
                  CounterHandlers.Metadata.SERVICE_NAME))
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings =
          [
              "eitherSleepOrCall",
              "callDifferentMethod",
              "backgroundInvokeWithDifferentTargets",
              "setDifferentKey"])
  @Execution(ExecutionMode.CONCURRENT)
  fun method(handlerName: String, @InjectClient ingressClient: Client) = runTest {
    // Increment the count first, this makes sure that the counter service is there.
    val c = CounterClient.fromClient(ingressClient, handlerName)
    c.add(1, idempotentCallOptions)

    assertThatThrownBy {
          ingressClient.call(
              Request.of(
                      Target.virtualObject(
                          NonDeterministicHandlers.Metadata.SERVICE_NAME, handlerName, handlerName),
                      Serde.VOID,
                      Serde.VOID,
                      null)
                  .also { idempotentCallOptions(it) })
        }
        .isNotNull()

    await withAlias "counter was not incremented" untilAsserted { assertThat(c.get()).isEqualTo(1) }
  }
}
