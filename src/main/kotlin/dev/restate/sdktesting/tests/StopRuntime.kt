// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import dev.restate.sdk.client.DefaultClient
import dev.restate.sdktesting.contracts.CounterClient
import dev.restate.sdktesting.contracts.CounterDefinitions
import dev.restate.sdktesting.infra.*
import java.net.http.HttpClient
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("always-suspending")
@Tag("only-single-node")
class StopRuntime {

  companion object {
    @JvmStatic
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(ServiceSpec.defaultBuilder().withServices(CounterDefinitions.SERVICE_NAME))
    }
  }

  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @Test
  fun startAndStopRuntimeRetainsTheState(
      @InjectContainerHandle(RESTATE_RUNTIME) runtimeHandle: ContainerHandle
  ) = runTest {
    // We instantiate the client manually, in order to close it before killing and restarting
    var httpClient = HttpClient.newHttpClient()
    var ingressClient =
        DefaultClient.of(
            httpClient, "http://127.0.0.1:${runtimeHandle.getMappedPort(8080)!!}", HashMap())
    val res1 = CounterClient.fromClient(ingressClient, "my-key").add(1, idempotentCallOptions())
    assertThat(res1.oldValue).isEqualTo(0)
    assertThat(res1.newValue).isEqualTo(1)

    // Close the HTTP client to avoid keeping around dangling connections.
    httpClient.close()

    // Stop and start the runtime
    runtimeHandle.terminateAndRestart()

    await.timeout(Duration.of(60, ChronoUnit.SECONDS)) withAlias
        "second add" untilAsserted
        {
          // We need a new client, because on restarts docker might mess up the exposed ports.
          // NotFunky but true...
          val httpClient =
              HttpClient.newBuilder().connectTimeout(5.seconds.toJavaDuration()).build()
          val ingressClient =
              DefaultClient.of(
                  httpClient, "http://127.0.0.1:${runtimeHandle.getMappedPort(8080)!!}", HashMap())
          val res2 =
              CounterClient.fromClient(ingressClient, "my-key").add(2, idempotentCallOptions())
          assertThat(res2.oldValue).isEqualTo(1)
          assertThat(res2.newValue).isEqualTo(3)
        }
  }
}
