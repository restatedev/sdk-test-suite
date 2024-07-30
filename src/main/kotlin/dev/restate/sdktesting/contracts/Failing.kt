// Copyright (c) 2024 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate e2e tests,
// which are released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/e2e/blob/main/LICENSE
package dev.restate.sdktesting.contracts

import dev.restate.sdk.annotation.*
import dev.restate.sdk.kotlin.ObjectContext

@VirtualObject
interface Failing {
  @Handler suspend fun terminallyFailingCall(context: ObjectContext, errorMessage: String)

  @Handler
  suspend fun callTerminallyFailingCall(context: ObjectContext, errorMessage: String): String

  @Handler suspend fun invokeExternalAndHandleFailure(context: ObjectContext): String

  @Handler suspend fun failingCallWithEventualSuccess(context: ObjectContext): Int

  @Handler suspend fun failingSideEffectWithEventualSuccess(context: ObjectContext): Int

  @Handler suspend fun terminallyFailingSideEffect(context: ObjectContext, errorMessage: String)
}
