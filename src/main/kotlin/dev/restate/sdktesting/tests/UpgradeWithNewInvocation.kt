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
import dev.restate.admin.model.RegisterDeploymentRequest
import dev.restate.admin.model.RegisterDeploymentRequestAnyOf
import dev.restate.client.Client
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.*
import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("always-suspending")
class UpgradeWithNewInvocation {

  companion object {
    private val UPGRADE_TEST_ENV = "UPGRADETEST_VERSION"

    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.builder("version1")
              .withServices(
                  TestUtilsServiceMetadata.SERVICE_NAME,
                  ListObjectMetadata.SERVICE_NAME,
                  AwakeableHolderMetadata.SERVICE_NAME)
              .withEnv(UPGRADE_TEST_ENV, "v1"))
      withServiceSpec(
          ServiceSpec.builder("version2")
              .skipRegistration()
              .withServices(TestUtilsServiceMetadata.SERVICE_NAME)
              .withEnv(UPGRADE_TEST_ENV, "v2"))
    }

    fun registerService2(metaURL: URI) {
      val client = DeploymentApi(ApiClient().setHost(metaURL.host).setPort(metaURL.port))
      client.createDeployment(
          RegisterDeploymentRequest(
              RegisterDeploymentRequestAnyOf().uri("http://version2:9080/").force(false)))
    }
  }

  @Test
  fun executesNewInvocationWithLatestServiceRevisions(
      @InjectClient ingressClient: Client,
      @InjectAdminURI adminURI: URI
  ) = runTest {
    val testUtilsClient = TestUtilsServiceClient.fromClient(ingressClient)

    // Execute the first request
    val firstResult = testUtilsClient.getEnvVariable(UPGRADE_TEST_ENV, idempotentCallOptions)
    assertThat(firstResult).isEqualTo("v1")

    // Now register the update
    registerService2(adminURI)

    // After the update, the runtime might not immediately propagate the usage of the new version
    // (this effectively depends on implementation details).
    // For this reason, we try to invoke the upgrade test method several times until we see the new
    // version running
    await withAlias
        "should now use service v2" untilAsserted
        {
          assertThat(testUtilsClient.getEnvVariable(UPGRADE_TEST_ENV)).isEqualTo("v2")
        }
  }
}
