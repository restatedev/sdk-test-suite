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

@Serializable data class TestEvent(val id: String, val value: Long)

@Service(name = "EventHandler")
interface EventHandler {
  @Handler suspend fun handle(context: Context, event: TestEvent)
}
