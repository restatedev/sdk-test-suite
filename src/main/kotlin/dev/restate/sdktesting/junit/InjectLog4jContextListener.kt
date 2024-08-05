// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.junit

import kotlin.jvm.optionals.getOrNull
import org.apache.logging.log4j.ThreadContext
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan

class InjectLog4jContextListener(val suiteName: String) : TestExecutionListener {

  companion object {
    const val TEST_NAME = "test"
  }

  @Volatile var testPlan: TestPlan? = null

  override fun testPlanExecutionStarted(testPlan: TestPlan) {
    this.testPlan = testPlan
  }

  override fun executionStarted(testIdentifier: TestIdentifier) {
    if (testIdentifier.isTest || testIdentifier.source.getOrNull() is ClassSource) {
      val displayName = describeTestIdentifier(suiteName, testPlan!!, testIdentifier)
      ThreadContext.put(TEST_NAME, displayName)
    }
  }

  override fun executionFinished(
      testIdentifier: TestIdentifier,
      testExecutionResult: TestExecutionResult
  ) {
    ThreadContext.remove(TEST_NAME)
  }
}
