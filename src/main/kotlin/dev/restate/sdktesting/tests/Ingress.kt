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
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.*
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

class Ingress {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder().withServices(TestUtilsServiceHandlers.Metadata.SERVICE_NAME))
      // We need the short cleanup interval b/c of the tests with the idempotent invoke.
      withEnv("RESTATE_WORKER__CLEANUP_INTERVAL", "1s")
    }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  fun headersPassThrough(@InjectClient ingressClient: Client) = runTest {
    val headerName = "x-my-custom-header"
    val headerValue = "x-my-custom-value"

    assertThat(
            TestUtilsServiceClient.fromClient(ingressClient).echoHeaders {
              idempotencyKey = UUID.randomUUID().toString()
              headers = mapOf(headerName to headerValue)
            })
        .containsEntry(headerName, headerValue)
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
