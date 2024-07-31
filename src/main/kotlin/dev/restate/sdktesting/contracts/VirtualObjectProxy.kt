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
data class Request(
    val componentName: String,
    val key: String,
    val handlerName: String,
    val message: ByteArray
)

@Service
interface VirtualObjectProxy {
  @Handler suspend fun call(context: Context, request: Request): ByteArray

  @Handler suspend fun oneWayCall(context: Context, request: Request)

  @Handler suspend fun getRetryCount(context: Context, request: Request): Int
}
