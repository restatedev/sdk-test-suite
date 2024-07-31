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
import dev.restate.sdktesting.contracts.CoordinatorClient
import dev.restate.sdktesting.infra.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.random.nextLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
      withServiceSpec(ServiceSpec.DEFAULT)
    }

    private val DEFAULT_SLEEP_DURATION = 4.seconds
  }

  private suspend fun asyncSleepTest(
      ingressClient: Client,
      sleepDuration: Duration = DEFAULT_SLEEP_DURATION,
      action: () -> Unit
  ) {
    val start = System.nanoTime()
    val job = coroutineScope {
      launch(Dispatchers.Default) {
        CoordinatorClient.fromClient(ingressClient).sleep(sleepDuration.inWholeMilliseconds)
      }
    }
    delay(
        Random.nextLong(
                (sleepDuration / 4).inWholeMilliseconds..(sleepDuration / 2).inWholeMilliseconds)
            .milliseconds)

    action()

    job.join()

    assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(sleepDuration.inWholeNanoseconds)
  }

  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @Test
  fun sleepAndKillServiceEndpoint(
      @InjectClient ingressClient: Client,
      @InjectContainerHandle(ServiceSpec.DEFAULT_SERVICE_NAME) coordinatorContainer: ContainerHandle
  ) {
    runTest(timeout = 15.seconds) {
      asyncSleepTest(ingressClient) { coordinatorContainer.killAndRestart() }
    }
  }

  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @Test
  fun sleepAndTerminateServiceEndpoint(
      @InjectClient ingressClient: Client,
      @InjectContainerHandle(ServiceSpec.DEFAULT_SERVICE_NAME) coordinatorContainer: ContainerHandle
  ) {
    runTest(timeout = 15.seconds) {
      asyncSleepTest(ingressClient) { coordinatorContainer.terminateAndRestart() }
    }
  }
}
