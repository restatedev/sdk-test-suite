// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import dev.restate.sdk.client.Client
import dev.restate.sdktesting.contracts.TestUtilsServiceClient
import dev.restate.sdktesting.contracts.TestUtilsServiceDefinitions
import dev.restate.sdktesting.infra.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.random.nextLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension

// -- Simple sleep tests

// -- Sleep tests with terminations/killings of runtime/service endpoint

@Tag("always-suspending")
class SleepWithFailures {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder().withServices(TestUtilsServiceDefinitions.SERVICE_NAME))
    }

    private val DEFAULT_SLEEP_DURATION = 4.seconds
  }

  private suspend fun asyncSleepTest(
      ingressClient: Client,
      sleepDuration: Duration = DEFAULT_SLEEP_DURATION,
      action: suspend () -> Unit
  ) {
    val start = TimeSource.Monotonic.markNow()
    val job = coroutineScope {
      launch {
        TestUtilsServiceClient.fromClient(ingressClient)
            .sleepConcurrently(listOf(sleepDuration.inWholeMilliseconds))
      }
    }
    delay(
        Random.nextLong(
                (sleepDuration / 4).inWholeMilliseconds..(sleepDuration / 2).inWholeMilliseconds)
            .milliseconds)

    action()

    job.join()

    assertThat(start.elapsedNow().toJavaDuration())
        .isGreaterThanOrEqualTo(sleepDuration.toJavaDuration())
  }

  @Timeout(value = 45, unit = TimeUnit.SECONDS)
  @Test
  fun sleepAndKillServiceEndpoint(
      @InjectClient ingressClient: Client,
      @InjectContainerHandle(ServiceSpec.DEFAULT_SERVICE_NAME) coordinatorContainer: ContainerHandle
  ) {
    runTest(timeout = 30.seconds) {
      asyncSleepTest(ingressClient) { coordinatorContainer.killAndRestart() }
    }
  }

  @Timeout(value = 45, unit = TimeUnit.SECONDS)
  @Test
  fun sleepAndTerminateServiceEndpoint(
      @InjectClient ingressClient: Client,
      @InjectContainerHandle(ServiceSpec.DEFAULT_SERVICE_NAME) coordinatorContainer: ContainerHandle
  ) {
    runTest { asyncSleepTest(ingressClient) { coordinatorContainer.terminateAndRestart() } }
  }
}
