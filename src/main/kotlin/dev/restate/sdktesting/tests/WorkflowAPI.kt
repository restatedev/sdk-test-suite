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
import dev.restate.client.SendResponse.SendStatus
import dev.restate.client.kotlin.*
import dev.restate.sdktesting.contracts.BlockAndWaitWorkflow
import dev.restate.sdktesting.infra.*
import java.util.*
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
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
      withServiceSpec(ServiceSpec.defaultBuilder().withServices(BlockAndWaitWorkflow::class))
    }
  }

  @Test
  @DisplayName("Set and resolve durable promise leads to completion")
  @Execution(ExecutionMode.CONCURRENT)
  fun setAndResolve(@InjectClient ingressClient: Client) = runTest {
    val workflowId = UUID.randomUUID().toString()
    val client = ingressClient.toWorkflow<BlockAndWaitWorkflow>(workflowId)

    val sendResponse = client.request { run("Francesco") }.send()
    assertThat(sendResponse.sendStatus()).isEqualTo(SendStatus.ACCEPTED)
    val workflowHandle = ingressClient.workflowHandle<String>("BlockAndWaitWorkflow", workflowId)

    // Wait state is set
    await withAlias
        "state is not blank" untilAsserted
        {
          assertThat(client.request { getState() }.call().response).isNotBlank
        }

    client.request { unblock("Till") }.options(idempotentCallOptions).call()

    assertThat(workflowHandle.attachSuspend().response).isEqualTo("Till")

    // Can call get output again
    assertThat(workflowHandle.getOutputSuspend().response.value).isEqualTo("Till")

    // Re-submit should have no effect
    val secondSendResponse = client.request { run("Francesco") }.send()
    assertThat(secondSendResponse.sendStatus()).isEqualTo(SendStatus.PREVIOUSLY_ACCEPTED)
    assertThat(secondSendResponse.invocationId()).isEqualTo(sendResponse.invocationId())
    assertThat(workflowHandle.getOutputSuspend().response.value).isEqualTo("Till")
  }
}
