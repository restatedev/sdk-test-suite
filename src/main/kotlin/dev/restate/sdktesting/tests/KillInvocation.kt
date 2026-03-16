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
import dev.restate.client.Client
import dev.restate.client.kotlin.*
import dev.restate.sdktesting.contracts.AwakeableHolder
import dev.restate.sdktesting.contracts.KillTest
import dev.restate.sdktesting.infra.*
import java.net.URI
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class KillInvocation {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(
                  KillTest.Runner::class, KillTest.Singleton::class, AwakeableHolder::class))
    }
  }

  @Test
  fun kill(@InjectClient ingressClient: Client, @InjectAdminURI adminURI: URI) = runTest {
    val key = UUID.randomUUID().toString()
    val id =
        ingressClient
            .toVirtualObject<KillTest.Runner>(key)
            .request { startCallTree() }
            .options(idempotentCallOptions)
            .send()
            .invocationId()
    val awakeableHolderClient = ingressClient.toVirtualObject<AwakeableHolder>(key)
    // With this synchronization point we make sure the call tree has been built before killing it.
    await withAlias
        "awakeable is registered" untilAsserted
        {
          assertThat(awakeableHolderClient.request { hasAwakeable() }.call().response).isTrue()
        }
    awakeableHolderClient.request { unlock("cancel") }.options(idempotentCallOptions).call()

    // Kill the invocation
    val client = InvocationApi(ApiClient().setHost(adminURI.host).setPort(adminURI.port))

    // The termination signal might arrive before the blocking call to the cancel singleton was
    // made, so we need to retry.
    await withAlias "verify test" untilAsserted { client.killInvocation(id) }

    await withAlias
        "singleton service is unlocked after killing the call tree" untilAsserted
        {
          ingressClient.toVirtualObject<KillTest.Singleton>(key).request { isUnlocked() }.call()
        }
  }
}
