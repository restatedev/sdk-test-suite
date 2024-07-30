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
import dev.restate.sdk.kotlin.SharedWorkflowContext
import dev.restate.sdk.kotlin.WorkflowContext

@Workflow
interface WorkflowAPIBlockAndWait {
  @Workflow suspend fun run(context: WorkflowContext, input: String): String

  @Shared suspend fun unblock(context: SharedWorkflowContext, output: String)

  @Shared suspend fun getState(context: SharedWorkflowContext): String?
}
