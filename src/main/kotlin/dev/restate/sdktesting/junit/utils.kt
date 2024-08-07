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
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan

fun describeTestIdentifier(
    testSuite: String,
    testPlan: TestPlan,
    identifier: TestIdentifier?
): String {
  if (identifier == null || identifier.parentId.isEmpty) {
    return testSuite
  }
  val parent =
      describeTestIdentifier(testSuite, testPlan, testPlan.getParent(identifier).getOrNull())
  return "$parent => ${identifier.displayName}"
}

fun classSimpleName(clz: String): String {
  return clz.substringAfterLast('.')
}
