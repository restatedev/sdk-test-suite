// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import dev.restate.admin.api.DeploymentApi
import dev.restate.admin.client.ApiClient
import dev.restate.admin.client.ApiException
import dev.restate.admin.model.RegisterDeploymentRequest
import dev.restate.admin.model.RegisterDeploymentRequestAnyOf
import dev.restate.client.Client
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.InterpretRequest
import dev.restate.sdktesting.infra.*
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.rnorth.ducttape.unreliables.Unreliables

private const val RETRY_SLEEP_DURATION_MS = 30L

@Tag("always-suspending")
class UpgradeWithNewInvocation {

  companion object {
    private val UPGRADE_TEST_ENV = "UPGRADETEST_VERSION"

    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.builder("version1")
              .withServices(VirtualObjectCommandInterpreterHandlers.Metadata.SERVICE_NAME)
              .withEnv(UPGRADE_TEST_ENV, "v1"))
      withServiceSpec(
          ServiceSpec.builder("version2")
              .skipRegistration()
              .withServices(VirtualObjectCommandInterpreterHandlers.Metadata.SERVICE_NAME)
              .withEnv(UPGRADE_TEST_ENV, "v2"))
    }

    fun registerServiceEndpoint(metaURL: URI, serviceEndpoint: String) {
      val client = DeploymentApi(ApiClient().setHost(metaURL.host).setPort(metaURL.port))
      val request =
          RegisterDeploymentRequest(
              RegisterDeploymentRequestAnyOf().uri(serviceEndpoint).force(false))

      Unreliables.retryUntilSuccess(20, TimeUnit.SECONDS) {
        try {
          return@retryUntilSuccess client.createDeployment(request)
        } catch (e: ApiException) {
          Thread.sleep(RETRY_SLEEP_DURATION_MS)
          throw IllegalStateException(
              "Error when discovering endpoint $serviceEndpoint, got status code ${e.code} with body: ${e.responseBody}",
              e)
        }
      }
    }
  }

  @Test
  fun executesNewInvocationWithLatestServiceRevisions(
      @InjectClient ingressClient: Client,
      @InjectAdminURI adminURI: URI
  ) = runTest {
    val interpreter =
        VirtualObjectCommandInterpreterClient.fromClient(
            ingressClient, UUID.randomUUID().toString())

    // Execute the first request
    val firstResult =
        interpreter.interpretCommands(
            InterpretRequest.getEnvVariable(UPGRADE_TEST_ENV), idempotentCallOptions)
    assertThat(firstResult).isEqualTo("v1")

    // Now register the update
    registerServiceEndpoint(adminURI, "http://version2:9080/")

    // After the update, the runtime might not immediately propagate the usage of the new version
    // (this effectively depends on implementation details).
    // For this reason, we try to invoke the upgrade test method several times until we see the new
    // version running
    await withAlias
        "should now use service v2" untilAsserted
        {
          assertThat(
                  interpreter.interpretCommands(InterpretRequest.getEnvVariable(UPGRADE_TEST_ENV)))
              .isEqualTo("v2")
        }
  }
}
