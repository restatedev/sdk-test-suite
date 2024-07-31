// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.junit

object TestSuites {
  val DEFAULT_SUITE = TestSuite("default", emptyMap(), "none() | always-suspending")
  val ALWAYS_SUSPENDING_SUITE =
      TestSuite(
          "alwaysSuspending",
          mapOf("RESTATE_WORKER__INVOKER__INACTIVITY_TIMEOUT" to "0s"),
          "always-suspending | only-always-suspending")
  val SINGLE_THREAD_SINGLE_PARTITION_SUITE =
      TestSuite(
          "singleThreadSinglePartition",
          mapOf(
              "RESTATE_WORKER__BOOTSTRAP_NUM_PARTITIONS" to "1",
              "RESTATE_DEFAULT_THREAD_POOL_SIZE" to "1",
          ),
          "none() | always-suspending")
  val LAZY_STATE_SUITE =
      TestSuite(
          "lazyState",
          mapOf(
              "RESTATE_WORKER__INVOKER__DISABLE_EAGER_STATE" to "true",
          ),
          "lazy-state")
  val PERSISTED_TIMERS_SUITE =
      TestSuite(
          "persistedTimers", mapOf("RESTATE_WORKER__NUM_TIMERS_IN_MEMORY_LIMIT" to "1"), "timers")

  fun allSuites(): List<TestSuite> {
    return listOf(
        DEFAULT_SUITE,
        ALWAYS_SUSPENDING_SUITE,
        SINGLE_THREAD_SINGLE_PARTITION_SUITE,
        LAZY_STATE_SUITE,
        PERSISTED_TIMERS_SUITE)
  }

  fun resolveSuites(suite: String?): List<TestSuite> {
    return when (suite ?: "all") {
      "all" -> allSuites()
      else -> {
        var result = listOf<TestSuite>()
        for (configuration in suite!!.split(',')) {
          result =
              result +
                  when (configuration) {
                    DEFAULT_SUITE.name -> listOf(DEFAULT_SUITE)
                    ALWAYS_SUSPENDING_SUITE.name -> listOf(ALWAYS_SUSPENDING_SUITE)
                    SINGLE_THREAD_SINGLE_PARTITION_SUITE.name ->
                        listOf(SINGLE_THREAD_SINGLE_PARTITION_SUITE)
                    LAZY_STATE_SUITE.name -> listOf(LAZY_STATE_SUITE)
                    PERSISTED_TIMERS_SUITE.name -> listOf(PERSISTED_TIMERS_SUITE)
                    else -> {
                      throw IllegalArgumentException("Unexpected suite name $suite")
                    }
                  }
        }
        result
      }
    }
  }
}
