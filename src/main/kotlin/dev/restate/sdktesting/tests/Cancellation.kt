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
import dev.restate.admin.client.ApiException
import dev.restate.client.Client
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.*
import java.net.URI
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

@Tag("always-suspending")
class Cancellation {
  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(
                  CancelTestRunnerHandlers.Metadata.SERVICE_NAME,
                  CancelTestBlockingServiceHandlers.Metadata.SERVICE_NAME,
                  AwakeableHolderHandlers.Metadata.SERVICE_NAME,
                  ProxyHandlers.Metadata.SERVICE_NAME,
                  TestUtilsServiceHandlers.Metadata.SERVICE_NAME))
    }
  }

  @ParameterizedTest(name = "cancel blocked invocation on {0} from Admin API")
  @EnumSource(value = BlockingOperation::class)
  fun cancelFromAdminAPI(
      blockingOperation: BlockingOperation,
      @InjectClient ingressClient: Client,
      @InjectAdminURI adminURI: URI,
  ) = runTest {
    val key = UUID.randomUUID().toString()
    val cancelTestClient = CancelTestRunnerClient.fromClient(ingressClient, key)
    val blockingServiceClient = CancelTestBlockingServiceClient.fromClient(ingressClient, key)

    val id =
        cancelTestClient
            .send()
            .startTest(blockingOperation, init = idempotentCallOptions)
            .invocationId()

    val awakeableHolderClient = AwakeableHolderClient.fromClient(ingressClient, key)
    await withAlias
        "awakeable is registered" untilAsserted
        {
          assertThat(awakeableHolderClient.hasAwakeable()).isTrue()
        }
    awakeableHolderClient.unlock("cancel", idempotentCallOptions)

    val client = InvocationApi(ApiClient().setHost(adminURI.host).setPort(adminURI.port))

    // The termination signal might arrive before the blocking call to the cancel singleton was
    // made, so we need to retry.
    await withAlias
        "verify test" untilAsserted
        {
          try {
            client.cancelInvocation(id)
          } catch (e: ApiException) {
            if (!(e.code == 409 || e.code == 404)) {
              throw e
            }
            // Already completed/cancelled
          }
          withTimeout(1.seconds) { cancelTestClient.verifyTest() }
        }

    // Check that the singleton service is unlocked
    await withAlias
        "blocking service is unlocked" untilAsserted
        {
          blockingServiceClient.isUnlocked()
        }
  }

  @ParameterizedTest(name = "cancel blocked invocation on {0} from Context")
  @EnumSource(value = BlockingOperation::class)
  fun cancelFromContext(
      blockingOperation: BlockingOperation,
      @InjectClient ingressClient: Client,
  ) = runTest {
    val key = UUID.randomUUID().toString()
    val cancelTestClient = CancelTestRunnerClient.fromClient(ingressClient, key)
    val blockingServiceClient = CancelTestBlockingServiceClient.fromClient(ingressClient, key)
    val proxyClient = ProxyClient.fromClient(ingressClient)
    val testUtilsClient = TestUtilsServiceClient.fromClient(ingressClient)

    val id =
        proxyClient.oneWayCall(
            ProxyRequest(
                serviceName = CancelTestRunnerHandlers.Metadata.SERVICE_NAME,
                virtualObjectKey = key,
                handlerName = "startTest",
                message = Json.encodeToString(blockingOperation).toByteArray()),
            idempotentCallOptions)

    val awakeableHolderClient = AwakeableHolderClient.fromClient(ingressClient, key)

    await withAlias
        "awakeable is registered" untilAsserted
        {
          assertThat(awakeableHolderClient.hasAwakeable()).isTrue()
        }

    awakeableHolderClient.unlock("cancel", idempotentCallOptions)

    // The termination signal might arrive before the blocking call to the cancel singleton was
    // made, so we need to retry.
    await
        .ignoreExceptionsInstanceOf(TimeoutCancellationException::class.java)
        .ignoreExceptionsInstanceOf(ApiException::class.java)
        .until {
          runBlocking {
            testUtilsClient.cancelInvocation(id, idempotentCallOptions)
            withTimeout(1.seconds) { cancelTestClient.verifyTest(idempotentCallOptions) }
          }
        }

    // Check that the singleton service is unlocked
    await withAlias
        "blocking service is unlocked" untilAsserted
        {
          blockingServiceClient.isUnlocked()
        }
  }
}
