// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import dev.restate.common.Request
import dev.restate.sdktesting.contracts.VirtualObjectCommandInterpreter
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.kotlin.additionalLoggingContext
import org.awaitility.core.ConditionFactory

val idempotentCallOptions: Request.Builder<*, *>.() -> Unit = {
  idempotencyKey = UUID.randomUUID().toString()
}

private val LOG = LogManager.getLogger("dev.restate.sdktesting.tests")

suspend infix fun ConditionFactory.untilAsserted(fn: suspend () -> Unit) {
  withContext(currentCoroutineContext() + Dispatchers.IO) {
    val coroutineContext = currentCoroutineContext()
    this@untilAsserted.ignoreExceptionsMatching { it !is TimeoutCancellationException }
        .logging { LOG.info(it) }
        .pollInSameThread()
        .untilAsserted { runBlocking(coroutineContext) { fn() } }
  }
}

fun runTest(timeout: Duration = 60.seconds, testBody: suspend TestScope.() -> Unit) =
    runTest(context = additionalLoggingContext(), timeout = timeout, testBody = testBody)

fun VirtualObjectCommandInterpreter.InterpretRequest.Companion.getEnvVariable(env: String) =
    VirtualObjectCommandInterpreter.InterpretRequest(
        listOf(VirtualObjectCommandInterpreter.GetEnvVariable(env)))

fun VirtualObjectCommandInterpreter.InterpretRequest.Companion.awaitAwakeable(
    awakeableKey: String
) =
    VirtualObjectCommandInterpreter.InterpretRequest(
        listOf(
            VirtualObjectCommandInterpreter.AwaitOne(
                VirtualObjectCommandInterpreter.CreateAwakeable(awakeableKey))))
