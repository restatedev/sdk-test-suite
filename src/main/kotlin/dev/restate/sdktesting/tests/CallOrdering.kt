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
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.InjectClient
import dev.restate.sdktesting.infra.RestateDeployerExtension
import dev.restate.sdktesting.infra.ServiceSpec
import java.util.UUID
import java.util.stream.Stream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Test the ordering is respected between invoke and background invoke */
class CallOrdering {
  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(ProxyDefinitions.SERVICE_NAME, ListObjectDefinitions.SERVICE_NAME))
    }

    @JvmStatic
    fun ordering(): Stream<Arguments> {
      // To enforce ordering wrt listClient.clear(...) executed in the test code,
      // the last call must be sync!
      return Stream.of(
          Arguments.of(booleanArrayOf(true, false, false)),
          Arguments.of(booleanArrayOf(false, true, false)),
          Arguments.of(
              booleanArrayOf(true, true, false),
          ))
    }
  }

  @ParameterizedTest
  @MethodSource
  @Execution(ExecutionMode.CONCURRENT)
  fun ordering(
      ordering: BooleanArray,
      @InjectClient ingressClient: Client,
  ) = runTest {
    val listName = UUID.randomUUID().toString()

    ProxyClient.fromClient(ingressClient)
        .manyCalls(
            ordering.mapIndexed { index, executeAsBackgroundCall ->
              val proxyRequest =
                  ProxyRequest(
                      ListObjectDefinitions.SERVICE_NAME,
                      listName,
                      "append",
                      Json.encodeToString(index.toString()).encodeToByteArray())

              if (executeAsBackgroundCall) {
                ManyCallRequest(proxyRequest, true, false)
              } else {
                ManyCallRequest(proxyRequest, false, true)
              }
            })

    assertThat(ListObjectClient.fromClient(ingressClient, listName).clear())
        .containsExactly("0", "1", "2")
  }
}
