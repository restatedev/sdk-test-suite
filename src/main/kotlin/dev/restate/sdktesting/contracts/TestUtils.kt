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

/** Collection of various utilities/corner cases scenarios used by tests */
@Service(name = "TestUtilsService")
interface TestUtilsService {
  /** Just echo */
  @Handler suspend fun echo(context: Context, input: String): String

  /** Just echo but with uppercase */
  @Handler suspend fun uppercaseEcho(context: Context, input: String): String

  /** Echo ingress headers */
  @Handler suspend fun echoHeaders(context: Context): Map<String, String>

  /** Create an awakeable, register it to AwakeableHolder#hold, then await it. */
  @Handler suspend fun holdAwakeableAndAwait(ctx: Context, awakeableKey: String): String

  /** Create timers and await them all. Durations in milliseconds */
  @Handler suspend fun sleepConcurrently(context: Context, millisDuration: List<Long>)

  /**
   * Create an awakeable that is never resolved, and await it with Awaitable#orTimeout().
   *
   * @return true if the timeout was fired
   */
  @Handler suspend fun awakeableWithTimeout(context: Context, millisDuration: Long): Boolean

  /**
   * Invoke `ctx.run` incrementing a local variable counter (not a restate state key!).
   *
   * Returns the count value.
   *
   * This is used to verify acks will suspend when using the always suspend test-suite
   */
  @Handler suspend fun countExecutedSideEffects(context: Context, increments: Int): Int
}
