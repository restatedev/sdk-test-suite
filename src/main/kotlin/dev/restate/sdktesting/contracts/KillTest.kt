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
import dev.restate.sdk.kotlin.ObjectContext
import jdk.jfr.Name

interface KillTest {
  @VirtualObject
  @Name("KillTestRunner")
  interface Runner {
    @Handler suspend fun startCallTree(context: ObjectContext)
  }

  @VirtualObject
  @Name("KillTestSingleton")
  interface Singleton {
    @Handler suspend fun recursiveCall(context: ObjectContext)

    @Handler suspend fun isUnlocked(context: ObjectContext)
  }
}
