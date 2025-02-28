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
import dev.restate.sdktesting.contracts.FailingClient
import dev.restate.sdktesting.contracts.FailingMetadata
import dev.restate.sdktesting.infra.InjectClient
import dev.restate.sdktesting.infra.RestateDeployerExtension
import dev.restate.sdktesting.infra.ServiceSpec
import java.util.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Tag("always-suspending")
class RunRetry {
  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(ServiceSpec.defaultBuilder().withServices(FailingMetadata.SERVICE_NAME))
    }
  }

  @DisplayName("Run is retried until it succeeds")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun withSuccess(@InjectClient ingressClient: Client) = runTest {
    val attempts = 3

    assertThat(
            FailingClient.fromClient(ingressClient, UUID.randomUUID().toString())
                .sideEffectSucceedsAfterGivenAttempts(attempts, idempotentCallOptions))
        .isGreaterThanOrEqualTo(attempts)
  }

  @DisplayName("Run is retried until it exhausts the retry attempts")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun withExhaustedAttempts(@InjectClient ingressClient: Client) = runTest {
    val attempts = 3

    assertThat(
            FailingClient.fromClient(ingressClient, UUID.randomUUID().toString())
                .sideEffectFailsAfterGivenAttempts(attempts, idempotentCallOptions))
        .isGreaterThanOrEqualTo(attempts)
  }
}
