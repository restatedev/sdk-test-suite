// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import dev.restate.admin.api.ServiceApi
import dev.restate.admin.client.ApiClient
import dev.restate.admin.model.ModifyServiceRequest
import dev.restate.client.Client
import dev.restate.client.IngressException
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.*
import java.net.URI
import java.util.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.InstanceOfAssertFactories
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** Test supporting private services */
class PrivateService {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(
                  CounterHandlers.Metadata.SERVICE_NAME, ProxyHandlers.Metadata.SERVICE_NAME))
    }
  }

  @Test
  @DisplayName(
      "Make a handler ingress private and try to call it both directly and through a proxy service")
  fun privateService(
      @InjectAdminURI adminURI: URI,
      @InjectClient ingressClient: Client,
  ) = runTest {
    val adminServiceClient = ServiceApi(ApiClient().setHost(adminURI.host).setPort(adminURI.port))
    val counterId = UUID.randomUUID().toString()
    val counterClient = CounterClient.fromClient(ingressClient, counterId)

    counterClient.add(1, idempotentCallOptions)

    // Make the service private
    adminServiceClient.modifyService(
        CounterHandlers.Metadata.SERVICE_NAME, ModifyServiceRequest()._public(false))

    // Wait for the service to be private
    await withAlias
        "the service becomes private" untilAsserted
        {
          val ctx = currentCoroutineContext()
          assertThatThrownBy { runBlocking(ctx) { counterClient.get() } }
              .asInstanceOf(InstanceOfAssertFactories.type(IngressException::class.java))
              .returns(400, IngressException::getStatusCode)
        }

    // Send a request through the proxy client
    ProxyClient.fromClient(ingressClient)
        .oneWayCall(
            ProxyRequest(
                CounterHandlers.Metadata.SERVICE_NAME,
                counterId,
                "add",
                Json.encodeToString(1).encodeToByteArray()),
            idempotentCallOptions)

    // Make the service public again
    adminServiceClient.modifyService(
        CounterHandlers.Metadata.SERVICE_NAME, ModifyServiceRequest()._public(true))

    // Wait to get the correct count
    await withAlias
        "the service becomes public again" untilAsserted
        {
          assertThat(counterClient.get()).isEqualTo(2L)
        }
  }
}
