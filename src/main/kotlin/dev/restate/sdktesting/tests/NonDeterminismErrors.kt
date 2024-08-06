// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import dev.restate.sdk.client.Client
import dev.restate.sdk.common.Serde
import dev.restate.sdk.common.Target
import dev.restate.sdktesting.contracts.CounterClient
import dev.restate.sdktesting.contracts.CounterDefinitions
import dev.restate.sdktesting.contracts.NonDeterministicDefinitions
import dev.restate.sdktesting.infra.*
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Test non-determinism/journal mismatch checks in the SDKs. */
@Tag("only-always-suspending")
class NonDeterminismErrors {
  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withInvokerRetryPolicy(RetryPolicy.None)
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(
                  NonDeterministicDefinitions.SERVICE_NAME, CounterDefinitions.SERVICE_NAME))
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
    assertThatThrownBy {
          ingressClient.call(
              Target.virtualObject(
                  NonDeterministicDefinitions.SERVICE_NAME, handlerName, handlerName),
              Serde.VOID,
              Serde.VOID,
              null)
        }
        .isNotNull()

    // Assert the counter was not incremented
    assertThat(CounterClient.fromClient(ingressClient, handlerName).get()).isZero()
  }
}
