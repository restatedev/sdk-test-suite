// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.junit

import com.github.ajalt.mordant.terminal.Terminal
import kotlin.jvm.optionals.getOrNull
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan

class LogTestEventsToTerminalListener(val suiteName: String, val terminal: Terminal) :
    TestExecutionListener {
  @Volatile var testPlan: TestPlan? = null

  override fun testPlanExecutionStarted(testPlan: TestPlan) {
    this.testPlan = testPlan
  }

  override fun executionFinished(
      testIdentifier: TestIdentifier,
      testExecutionResult: TestExecutionResult
  ) {
    if (testIdentifier.isTest) {
      val name = describeTestIdentifier(suiteName, testPlan!!, testIdentifier)
      when (testExecutionResult.status!!) {
        TestExecutionResult.Status.SUCCESSFUL -> terminal.println("✅ $name")
        TestExecutionResult.Status.ABORTED -> terminal.println("❌ $name")
        TestExecutionResult.Status.FAILED -> {
          terminal.println("❌ $name")
        }
      }
    }
    if (testIdentifier.source.getOrNull() is ClassSource) {
      val name = describeTestIdentifier(suiteName, testPlan!!, testIdentifier)
      when (testExecutionResult.status!!) {
        TestExecutionResult.Status.ABORTED -> terminal.println("❌ $name init")
        TestExecutionResult.Status.FAILED -> {
          terminal.println("❌ $name init")
        }
        else -> {}
      }
    }
  }
}
