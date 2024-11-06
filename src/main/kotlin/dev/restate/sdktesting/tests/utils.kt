// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import dev.restate.sdk.client.CallRequestOptions
import java.lang.AssertionError
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.awaitility.core.ConditionFactory

fun idempotentCallOptions(): CallRequestOptions {
  return CallRequestOptions().withIdempotency(UUID.randomUUID().toString())
}

suspend infix fun ConditionFactory.untilAsserted(fn: suspend () -> Unit) {
  withContext(Dispatchers.IO) {
    val coroutineContext = currentCoroutineContext()
    this@untilAsserted.untilAsserted {
      runBlocking(coroutineContext) {
        try {
          fn()
        } catch (e: AssertionError) {
          // Let awaitility deal with it
          throw e
        } catch (e: Throwable) {
          // Boom this we rethrow as AssertionError, because we don't want exceptions
          throw AssertionError("untilAsserted failed: " + e.message, e)
        }
      }
    }
  }
}
