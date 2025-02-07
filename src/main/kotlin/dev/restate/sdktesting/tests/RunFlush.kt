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
import dev.restate.sdktesting.contracts.TestUtilsServiceClient
import dev.restate.sdktesting.contracts.TestUtilsServiceDefinitions
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

@Tag("only-always-suspending")
class RunFlush {
  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder().withServices(TestUtilsServiceDefinitions.SERVICE_NAME))
    }
  }

  @DisplayName("Run should wait on acknowledgements")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun flush(@InjectClient ingressClient: Client) = runTest {
    assertThat(
            TestUtilsServiceClient.fromClient(ingressClient)
                .countExecutedSideEffects(3, idempotentCallOptions()))
        .isEqualTo(0)
  }
}
