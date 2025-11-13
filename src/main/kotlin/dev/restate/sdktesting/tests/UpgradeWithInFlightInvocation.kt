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
import dev.restate.admin.model.RegisterHttpDeploymentRequest
import dev.restate.client.Client
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.AwaitOne
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.CreateAwakeable
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.GetEnvVariable
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.InterpretRequest
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter.ResolveAwakeable
import dev.restate.sdktesting.infra.*
import java.net.URI
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("always-suspending")
class UpgradeWithInFlightInvocation {

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

    fun registerService2(metaURL: URI) {
      val client = DeploymentApi(ApiClient().setHost(metaURL.host).setPort(metaURL.port))
      client.createDeployment(
          RegisterDeploymentRequest(
              RegisterHttpDeploymentRequest().uri("http://version2:9080/").force(false)))
    }
  }

  @Test
  fun inFlightInvocation(@InjectClient ingressClient: Client, @InjectAdminURI adminURI: URI) =
      runTest {
        val interpreter =
            VirtualObjectCommandInterpreterClient.fromClient(
                ingressClient, UUID.randomUUID().toString())
        val awakeableKey = "upgrade"

        interpreter
            .send()
            .interpretCommands(
                InterpretRequest(
                    listOf(
                        GetEnvVariable(UPGRADE_TEST_ENV),
                        AwaitOne(CreateAwakeable(awakeableKey)),
                        GetEnvVariable(UPGRADE_TEST_ENV),
                    )),
                init = idempotentCallOptions)

        // Await until awakeable is registered
        await withAlias
            "reach sync point" untilAsserted
            {
              assertThat(interpreter.hasAwakeable(awakeableKey)).isTrue
            }

        // Now register the update
        UpgradeWithNewInvocation.registerServiceEndpoint(adminURI, "http://version2:9080/")

        // Now let's resume the awakeable
        interpreter.resolveAwakeable(
            ResolveAwakeable(awakeableKey, "unlocked"), idempotentCallOptions)

        // Let's check the list the interpreter appended to contains always v1 env variables
        await withAlias
            "old invocation remains on v1" untilAsserted
            {
              assertThat(interpreter.getResults()).containsExactly("v1", "unlocked", "v1")
            }

        val newInterpreter =
            VirtualObjectCommandInterpreterClient.fromClient(
                ingressClient, UUID.randomUUID().toString())
        await withAlias
            "new invocations should use service v2" untilAsserted
            {
              assertThat(
                      newInterpreter.interpretCommands(
                          InterpretRequest.getEnvVariable(UPGRADE_TEST_ENV)))
                  .isEqualTo("v2")
            }
      }
}
