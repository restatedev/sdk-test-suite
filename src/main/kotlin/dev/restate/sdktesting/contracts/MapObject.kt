// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.contracts

import dev.restate.sdk.annotation.Handler
import dev.restate.sdk.annotation.VirtualObject
import dev.restate.sdk.kotlin.ObjectContext
import jdk.jfr.Name
import kotlinx.serialization.Serializable

@VirtualObject
@Name("MapObject")
interface MapObject {
  @Serializable data class Entry(val key: String, val value: String)

  /**
   * Set value in map.
   *
   * The individual entries should be stored as separate Restate state keys, and not in a single
   * state key
   */
  @Handler suspend fun set(context: ObjectContext, entry: Entry)

  /** Get value from map. */
  @Handler suspend fun get(context: ObjectContext, key: String): String

  /** Clear all entries */
  @Handler suspend fun clearAll(context: ObjectContext): List<Entry>
}
