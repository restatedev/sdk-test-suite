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
import dev.restate.sdktesting.contracts.CoordinatorClient
import dev.restate.sdktesting.infra.InjectClient
import dev.restate.sdktesting.infra.RestateDeployerExtension
import dev.restate.sdktesting.infra.ServiceSpec
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.random.nextLong
import kotlin.system.measureNanoTime
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Tag("always-suspending")
@Tag("timers")
class Sleep {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(ServiceSpec.DEFAULT)
    }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun sleep(@InjectClient ingressClient: Client) = runTest {
    val sleepDuration = 10.milliseconds

    val elapsed = measureNanoTime {
      CoordinatorClient.fromClient(ingressClient).sleep(sleepDuration.inWholeMilliseconds)
    }

    Assertions.assertThat(elapsed.nanoseconds).isGreaterThanOrEqualTo(sleepDuration)
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @Execution(ExecutionMode.CONCURRENT)
  fun manySleeps(@InjectClient ingressClient: Client) =
      runTest(timeout = 60.seconds) {
        val minSleepDuration = 10.milliseconds
        val maxSleepDuration = 50.milliseconds
        val sleepsPerInvocation = 20
        val concurrentSleepInvocations = 50

        val coordinatorClient = CoordinatorClient.fromClient(ingressClient)

        // Range is inclusive
        (1..concurrentSleepInvocations)
            .map {
              launch {
                coordinatorClient.manyTimers(
                    (1..sleepsPerInvocation).map {
                      Random.nextLong(
                          minSleepDuration.inWholeMilliseconds..maxSleepDuration
                                  .inWholeMilliseconds)
                    })
              }
            }
            .joinAll()
      }
}
