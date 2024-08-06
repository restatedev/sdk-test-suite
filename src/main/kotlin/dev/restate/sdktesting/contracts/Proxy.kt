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
import dev.restate.sdk.annotation.Service
import dev.restate.sdk.kotlin.Context
import kotlinx.serialization.Serializable

@Serializable
data class ProxyRequest(
    val serviceName: String,
    val virtualObjectKey: String?, // If null, the request is to a service
    val handlerName: String,
    // Bytes are encoded as array of numbers
    val message: ByteArray
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ProxyRequest) return false

    if (serviceName != other.serviceName) return false
    if (virtualObjectKey != other.virtualObjectKey) return false
    if (handlerName != other.handlerName) return false
    if (!message.contentEquals(other.message)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = serviceName.hashCode()
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

@Service(name = "Proxy")
interface Proxy {
  // Bytes are encoded as array of numbers
  @Handler suspend fun call(context: Context, request: ProxyRequest): ByteArray

  @Handler suspend fun oneWayCall(context: Context, request: ProxyRequest)

  @Handler suspend fun manyCalls(context: Context, requests: List<ManyCallRequest>)
}
