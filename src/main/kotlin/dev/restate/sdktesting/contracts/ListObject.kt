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

@VirtualObject
@Name("ListObject")
interface ListObject {
  /** Append a value to the list object */
  @Handler suspend fun append(value: String)

  /** Get current list */
  @Handler suspend fun get(): List<String>

  /** Clear list */
  @Handler suspend fun clear(): List<String>
}
