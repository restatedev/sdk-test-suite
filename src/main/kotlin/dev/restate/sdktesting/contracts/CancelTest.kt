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
import kotlinx.serialization.Serializable

@Serializable
enum class BlockingOperation {
  CALL,
  SLEEP,
  AWAKEABLE
}

interface CancelTest {

  @VirtualObject
  @Name("CancelTestRunner")
  interface Runner {
    @Exclusive suspend fun startTest(context: ObjectContext, operation: BlockingOperation)

    @Exclusive suspend fun verifyTest(context: ObjectContext): Boolean
  }

  @VirtualObject
  @Name("CancelTestBlockingService")
  interface BlockingService {
    @Exclusive suspend fun block(context: ObjectContext, operation: BlockingOperation)

    @Exclusive suspend fun isUnlocked(context: ObjectContext)
  }
}
