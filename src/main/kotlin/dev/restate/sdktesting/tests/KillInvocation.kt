// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate e2e tests,
// which are released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/e2e/blob/main/LICENSE

package dev.restate.sdktesting.tests

import dev.restate.admin.api.InvocationApi
import dev.restate.admin.client.ApiClient
import dev.restate.admin.model.TerminationMode
import dev.restate.sdk.client.Client
import dev.restate.sdktesting.contracts.AwakeableHolderClient
import dev.restate.sdktesting.contracts.KillTestRunnerClient
import dev.restate.sdktesting.contracts.KillTestSingletonClient
import dev.restate.sdktesting.infra.*
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class KillInvocation {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(ServiceSpec.DEFAULT)
    }
  }

  @Test
  fun kill(@InjectClient ingressClient: Client, @InjectMetaURL metaURL: URL) = runTest {
    val id = KillTestRunnerClient.fromClient(ingressClient).send().startCallTree().invocationId
    val awakeableHolderClient = AwakeableHolderClient.fromClient(ingressClient, "kill")

    // Await until AwakeableHolder has an awakeable and then complete it.
    // With this synchronization point we make sure the call tree has been built before killing it.
    await until { runBlocking { awakeableHolderClient.hasAwakeable() } }
    awakeableHolderClient.unlock("")

    // Kill the invocation
    val client = InvocationApi(ApiClient().setHost(metaURL.host).setPort(metaURL.port))
    client.terminateInvocation(id, TerminationMode.KILL)

    // Check that the singleton service is unlocked after killing the call tree
    KillTestSingletonClient.fromClient(ingressClient, "").isUnlocked()
  }
}
