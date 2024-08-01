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
import dev.restate.sdk.kotlin.SharedObjectContext
import kotlinx.serialization.Serializable

@Serializable data class CounterUpdateResponse(val oldValue: Long, val newValue: Long)

@VirtualObject(name = "Counter")
interface Counter {
  /** Add value to counter */
  @Handler suspend fun add(context: ObjectContext, value: Long): CounterUpdateResponse

  /** Add value to counter, then fail with a Terminal error */
  @Handler suspend fun addThenFail(context: ObjectContext, value: Long)

  /** Get count */
  @Shared suspend fun get(context: SharedObjectContext): Long

  /** Reset count */
  @Handler suspend fun reset(context: ObjectContext)
}
