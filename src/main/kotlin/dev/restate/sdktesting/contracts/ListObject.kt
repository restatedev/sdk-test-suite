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

@VirtualObject(name = "ListObject")
interface ListObject {
  @Handler suspend fun append(context: ObjectContext, value: String)

  @Handler suspend fun get(context: ObjectContext): List<String>

  @Handler suspend fun clear(context: ObjectContext): List<String>
}
