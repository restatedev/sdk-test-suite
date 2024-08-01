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
import dev.restate.sdktesting.contracts.AwakeableHolderDefinitions
import dev.restate.sdktesting.contracts.TestUtilsServiceClient
import dev.restate.sdktesting.contracts.TestUtilsServiceDefinitions
import dev.restate.sdktesting.infra.*
import java.time.Duration
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Tag("always-suspending")
class AwaitTimeout {
  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(
                  AwakeableHolderDefinitions.SERVICE_NAME,
                  TestUtilsServiceDefinitions.SERVICE_NAME))
    }
  }

  @Test
  @DisplayName("Test Awaitable#await(Duration)")
  @Execution(ExecutionMode.CONCURRENT)
  fun timeout(@InjectClient ingressClient: Client) = runTest {
    val timeout = Duration.ofMillis(100L)
    assertThat(
            TestUtilsServiceClient.fromClient(ingressClient)
                .awakeableWithTimeout(timeout.toMillis()))
        .isTrue
  }
}
