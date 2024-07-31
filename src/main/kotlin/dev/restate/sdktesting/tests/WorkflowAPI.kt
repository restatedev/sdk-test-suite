// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate e2e tests,
// which are released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/e2e/blob/main/LICENSE

package dev.restate.sdktesting.tests

import dev.restate.sdk.client.Client
import dev.restate.sdk.client.SendResponse.SendStatus
import dev.restate.sdktesting.contracts.WorkflowAPIBlockAndWaitClient
import dev.restate.sdktesting.infra.*
import java.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Tag("always-suspending")
class WorkflowAPI {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(ServiceSpec.DEFAULT)
    }
  }

  @Test
  @DisplayName("Set and resolve durable promise leads to completion")
  @Execution(ExecutionMode.CONCURRENT)
  fun setAndResolve(@InjectClient ingressClient: Client) = runTest {
    val client =
        WorkflowAPIBlockAndWaitClient.fromClient(ingressClient, UUID.randomUUID().toString())

    val sendResponse = client.submit("Francesco")
    assertThat(sendResponse.status).isEqualTo(SendStatus.ACCEPTED)

    // Wait state is set
    await untilCallTo { runBlocking { client.getState() } } matches { it != null }

    client.unblock("Till")

    assertThat(client.workflowHandle().attach()).isEqualTo("Till")

    // Can call get output again
    assertThat(client.workflowHandle().output.value).isEqualTo("Till")

    // Re-submit should have no effect
    val secondSendResponse = client.submit("Francesco")
    assertThat(secondSendResponse.status).isEqualTo(SendStatus.PREVIOUSLY_ACCEPTED)
    assertThat(secondSendResponse.invocationId).isEqualTo(sendResponse.invocationId)
    assertThat(client.workflowHandle().output.value).isEqualTo("Till")
  }
}
