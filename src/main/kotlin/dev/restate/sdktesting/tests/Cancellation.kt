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
import dev.restate.client.kotlin.*
import dev.restate.common.reflections.ReflectionUtils.extractServiceName
import dev.restate.sdktesting.contracts.AwakeableHolder
import dev.restate.sdktesting.contracts.CancelTest
import dev.restate.sdktesting.contracts.Proxy
import dev.restate.sdktesting.contracts.TestUtilsService
import dev.restate.sdktesting.infra.*
import java.net.URI
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout
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
                  CancelTest.Runner::class,
                  CancelTest.BlockingService::class,
                  AwakeableHolder::class,
                  Proxy::class,
                  TestUtilsService::class))
    }
  }

  @ParameterizedTest(name = "cancel blocked invocation on {0} from Admin API")
  @EnumSource(value = CancelTest.BlockingOperation::class)
  fun cancelFromAdminAPI(
      blockingOperation: CancelTest.BlockingOperation,
      @InjectClient ingressClient: Client,
      @InjectAdminURI adminURI: URI,
  ) = runTest {
    val key = UUID.randomUUID().toString()
    val cancelTestClient = ingressClient.toVirtualObject<CancelTest.Runner>(key)
    val blockingServiceClient = ingressClient.toVirtualObject<CancelTest.BlockingService>(key)

    val id =
        cancelTestClient
            .request { startTest(blockingOperation) }
            .options(idempotentCallOptions)
            .send()
            .invocationId()

    val awakeableHolderClient = ingressClient.toVirtualObject<AwakeableHolder>(key)
    await withAlias
        "awakeable is registered" untilAsserted
        {
          assertThat(awakeableHolderClient.request { hasAwakeable() }.call().response).isTrue()
        }
    awakeableHolderClient.request { unlock("cancel") }.options(idempotentCallOptions).call()

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
          withTimeout(1.seconds) { cancelTestClient.request { verifyTest() }.call() }
        }

    // Check that the singleton service is unlocked
    await withAlias
        "blocking service is unlocked" untilAsserted
        {
          blockingServiceClient.request { isUnlocked() }.call()
        }
  }

  @ParameterizedTest(name = "cancel blocked invocation on {0} from Context")
  @EnumSource(value = CancelTest.BlockingOperation::class)
  fun cancelFromContext(
      blockingOperation: CancelTest.BlockingOperation,
      @InjectClient ingressClient: Client,
  ) = runTest {
    val key = UUID.randomUUID().toString()
    val cancelTestClient = ingressClient.toVirtualObject<CancelTest.Runner>(key)
    val blockingServiceClient = ingressClient.toVirtualObject<CancelTest.BlockingService>(key)
    val proxyClient = ingressClient.toService<Proxy>()
    val testUtilsClient = ingressClient.toService<TestUtilsService>()

    val id =
        proxyClient
            .request {
              oneWayCall(
                  Proxy.ProxyRequest(
                      serviceName = extractServiceName(CancelTest.Runner::class.java),
                      virtualObjectKey = key,
                      handlerName = "startTest",
                      message = Json.encodeToString(blockingOperation).toByteArray()))
            }
            .options(idempotentCallOptions)
            .call()
            .response

    val awakeableHolderClient = ingressClient.toVirtualObject<AwakeableHolder>(key)

    await withAlias
        "awakeable is registered" untilAsserted
        {
          assertThat(awakeableHolderClient.request { hasAwakeable() }.call().response).isTrue()
        }

    awakeableHolderClient.request { unlock("cancel") }.options(idempotentCallOptions).call()

    // The termination signal might arrive before the blocking call to the cancel singleton was
    // made, so we need to retry.
    await withAlias
        "verify test" untilAsserted
        {
          testUtilsClient.request { cancelInvocation(id) }.options(idempotentCallOptions).call()
          withTimeout(1.seconds) {
            cancelTestClient.request { verifyTest() }.options(idempotentCallOptions).call()
          }
        }

    // Check that the singleton service is unlocked
    await withAlias
        "blocking service is unlocked" untilAsserted
        {
          blockingServiceClient.request { isUnlocked() }.call()
        }
  }
}
