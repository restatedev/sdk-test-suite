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
import dev.restate.sdktesting.contracts.TestUtilsServiceClient
import dev.restate.sdktesting.contracts.TestUtilsServiceMetadata
import dev.restate.sdktesting.infra.InjectClient
import dev.restate.sdktesting.infra.RestateDeployerExtension
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

class RawHandler {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec { services = listOf(TestUtilsServiceMetadata.SERVICE_NAME) }
    }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @DisplayName("Raw input and raw output")
  fun rawHandler(@InjectClient ingressClient: Client) = runTest {
    val bytes = Random.nextBytes(100)

    assertThat(
            TestUtilsServiceClient.fromClient(ingressClient).rawEcho(bytes, idempotentCallOptions))
        .isEqualTo(bytes)
  }
}
