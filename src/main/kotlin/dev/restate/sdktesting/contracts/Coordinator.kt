// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.contracts

import dev.restate.sdk.annotation.*
import dev.restate.sdk.kotlin.Context
import kotlinx.serialization.Serializable

@Serializable
data class CoordinatorComplexRequest(val sleepDurationMillis: Long, val requestValue: String)

@Serializable
data class CoordinatorInvokeSequentiallyRequest(
    val executeAsBackgroundCall: List<Boolean>,
    val listName: String
)

@Service(name = "Coordinator")
interface Coordinator {
  @Handler suspend fun sleep(context: Context, millisDuration: Long)

  @Handler suspend fun manyTimers(context: Context, millisDurations: List<Long>)

  @Handler suspend fun proxy(context: Context): String

  @Handler suspend fun complex(context: Context, complexRequest: CoordinatorComplexRequest): String

  @Handler suspend fun timeout(context: Context, millisDuration: Long): Boolean

  @Handler
  suspend fun invokeSequentially(context: Context, request: CoordinatorInvokeSequentiallyRequest)
}
