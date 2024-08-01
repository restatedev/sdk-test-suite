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
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.InjectClient
import dev.restate.sdktesting.infra.RestateDeployerExtension
import dev.restate.sdktesting.infra.ServiceSpec
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Tag("always-suspending")
class ServiceToServiceCommunication {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(ServiceSpec.DEFAULT)
    }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun regularCall(@InjectClient ingressClient: Client) = runTest {
    val proxyClient = ProxyClient.fromClient(ingressClient)

    // Send request twice
    assertThat(
            proxyClient.call(
                ProxyRequest(
                    TestUtilsServiceDefinitions.SERVICE_NAME,
                    null,
                    "uppercaseEcho",
                    Json.encodeToString("ping").encodeToByteArray())))
        .isEqualTo("PING")
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun oneWayCall(@InjectClient ingressClient: Client) = runTest {
    val counterId = UUID.randomUUID().toString()
    val proxyClient = ProxyClient.fromClient(ingressClient)
    val counterClient = CounterClient.fromClient(ingressClient, counterId)

    proxyClient.oneWayCall(
        ProxyRequest(
            CounterDefinitions.SERVICE_NAME,
            counterId,
            "add",
            Json.encodeToString(1).encodeToByteArray()))

    await untilAsserted { runBlocking { assertThat(counterClient.get()).isEqualTo(1L) } }
  }
}
