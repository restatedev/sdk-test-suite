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
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.ThreadContext
import org.awaitility.core.ConditionFactory

fun idempotentCallOptions(): CallRequestOptions {
  return CallRequestOptions().withIdempotency(UUID.randomUUID().toString())
}

private val LOG = LogManager.getLogger("dev.restate.sdktesting.tests")

suspend infix fun ConditionFactory.untilAsserted(fn: suspend () -> Unit) {
  val ctx = ThreadContext.getContext()
  withContext(currentCoroutineContext() + Dispatchers.IO) {
    val coroutineContext = currentCoroutineContext()
    this@untilAsserted.ignoreExceptions()
        .logging {
          ThreadContext.putAll(ctx)
          LOG.info(it)
        }
        .untilAsserted { runBlocking(coroutineContext) { fn() } }
  }
}
