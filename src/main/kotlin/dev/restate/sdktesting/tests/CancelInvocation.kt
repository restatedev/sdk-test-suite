// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import dev.restate.admin.api.InvocationApi
import dev.restate.admin.client.ApiClient
import dev.restate.admin.model.DeletionMode
import dev.restate.sdk.client.Client
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.*
import java.net.URL
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class CancelInvocation {
  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(
                  CancelTestRunnerDefinitions.SERVICE_NAME,
                  CancelTestBlockingServiceDefinitions.SERVICE_NAME,
                  AwakeableHolderDefinitions.SERVICE_NAME))
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

    val id =
        cancelTestClient.send().startTest(blockingOperation, idempotentCallOptions()).invocationId

    val awakeableHolderClient = AwakeableHolderClient.fromClient(ingressClient, "cancel")
    await withAlias
        "awakeable is registered" untilAsserted
        {
          assertThat(awakeableHolderClient.hasAwakeable()).isTrue()
        }
    awakeableHolderClient.unlock("cancel", idempotentCallOptions())

    val client = InvocationApi(ApiClient().setHost(metaURL.host).setPort(metaURL.port))

    // The termination signal might arrive before the blocking call to the cancel singleton was
    // made, so we need to retry.
    await withAlias
        "verify test" untilAsserted
        {
          client.deleteInvocation(id, DeletionMode.CANCEL)
          withTimeout(1.seconds) { cancelTestClient.verifyTest() }
        }

    // Check that the singleton service is unlocked
    await withAlias
        "blocking service is unlocked" untilAsserted
        {
          blockingServiceClient.isUnlocked()
        }
  }
}
