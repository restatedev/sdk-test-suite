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
data class ProxyRequest(
    val componentName: String,
    val virtualObjectKey: String?, // If null, the request is to a service
    val handlerName: String,
    val message: ByteArray
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ProxyRequest) return false

    if (componentName != other.componentName) return false
    if (virtualObjectKey != other.virtualObjectKey) return false
    if (handlerName != other.handlerName) return false
    if (!message.contentEquals(other.message)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = componentName.hashCode()
    result = 31 * result + (virtualObjectKey?.hashCode() ?: 0)
    result = 31 * result + handlerName.hashCode()
    result = 31 * result + message.contentHashCode()
    return result
  }
}

@Serializable
data class ManyCallRequest(
    val proxyRequest: ProxyRequest,
    /** If true, perform a one way call instead of a regular call */
    val oneWayCall: Boolean,
    /**
     * If await at the end, then perform the call as regular call, and collect all the futures to
     * wait at the end, before returning, instead of awaiting them immediately.
     */
    val awaitAtTheEnd: Boolean
)

@Service
interface Proxy {
  @Handler suspend fun call(context: Context, proxyRequest: ProxyRequest): ByteArray

  @Handler suspend fun oneWayCall(context: Context, proxyRequest: ProxyRequest)

  @Handler suspend fun manyCalls(context: Context, request: List<ManyCallRequest>)
}
