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
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.*
import java.net.URL
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class CancelInvocationTest {
  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(ServiceSpec.DEFAULT)
    }
  }

  @ParameterizedTest(name = "cancel blocked invocation on {0}")
  @EnumSource(value = BlockingOperation::class)
  fun cancelInvocation(
      blockingOperation: BlockingOperation,
      @InjectClient ingressClient: Client,
      @InjectMetaURL metaURL: URL,
  ) = runTest {
    val key = UUID.randomUUID().toString()
    val cancelTestClient = CancelTestRunnerClient.fromClient(ingressClient, key)
    val blockingServiceClient = CancelTestBlockingServiceClient.fromClient(ingressClient, key)

    val id = cancelTestClient.send().startTest(blockingOperation).invocationId

    val awakeableHolderClient = AwakeableHolderClient.fromClient(ingressClient, "cancel")

    await until { runBlocking { awakeableHolderClient.hasAwakeable() } }

    awakeableHolderClient.unlock("cancel")

    val client = InvocationApi(ApiClient().setHost(metaURL.host).setPort(metaURL.port))

    // The termination signal might arrive before the blocking call to the cancel singleton was
    // made, so we need to retry.
    await.ignoreException(TimeoutCancellationException::class.java).until {
      client.terminateInvocation(id, TerminationMode.CANCEL)
      runBlocking { withTimeout(1.seconds) { cancelTestClient.verifyTest() } }
    }

    // Check that the singleton service is unlocked
    blockingServiceClient.isUnlocked()
  }
}
