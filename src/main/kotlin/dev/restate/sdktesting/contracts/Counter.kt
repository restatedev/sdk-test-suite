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
import dev.restate.sdk.kotlin.ObjectContext
import kotlinx.serialization.Serializable

@Serializable data class CounterAddRequest(val oldValue: Long, val newValue: Long)

@Serializable data class CounterUpdateResponse(val oldValue: Long, val newValue: Long)

@VirtualObject(name = "Counter")
interface Counter {
  @Handler suspend fun add(context: ObjectContext, value: Long)

  @Handler suspend fun reset(context: ObjectContext)

  @Handler suspend fun addThenFail(context: ObjectContext, value: Long)

  @Handler suspend fun get(context: ObjectContext): Long

  @Handler suspend fun getAndAdd(context: ObjectContext, value: Long): CounterUpdateResponse

  @Handler suspend fun infiniteIncrementLoop(context: ObjectContext)
}
