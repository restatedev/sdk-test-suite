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
import dev.restate.sdk.kotlin.Context
import dev.restate.sdk.kotlin.ObjectContext

interface KillTest {
  @Service
  interface Runner {
    @Handler suspend fun startCallTree(context: Context)
  }

  @VirtualObject
  interface Singleton {
    @Handler suspend fun recursiveCall(context: ObjectContext)

    @Handler suspend fun isUnlocked(context: ObjectContext)
  }
}
