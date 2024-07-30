package dev.restate.sdktesting.junit

object Configurations {
  val DEFAULT_CONFIG = TestConfiguration("default", emptyMap(), "none() | always-suspending")
  val ALWAYS_SUSPENDING_CONFIG =
      TestConfiguration(
          "alwaysSuspending",
          mapOf("RESTATE_WORKER__INVOKER__INACTIVITY_TIMEOUT" to "0s"),
          "always-suspending | only-always-suspending")
  val SINGLE_THREAD_SINGLE_PARTITION_CONFIG =
      TestConfiguration(
          "singleThreadSinglePartition",
          mapOf(
              "RESTATE_WORKER__BOOTSTRAP_NUM_PARTITIONS" to "1",
              "RESTATE_DEFAULT_THREAD_POOL_SIZE" to "1",
          ),
          "none() | always-suspending")
  val LAZY_STATE_CONFIG =
      TestConfiguration(
          "lazyState",
          mapOf(
              "RESTATE_WORKER__INVOKER__DISABLE_EAGER_STATE" to "true",
          ),
          "lazy-state")
  val PERSISTED_TIMERS_CONFIG =
      TestConfiguration(
          "persistedTimers", mapOf("RESTATE_WORKER__NUM_TIMERS_IN_MEMORY_LIMIT" to "1"), "timers")

  fun allConfigurations(): List<TestConfiguration> {
    return listOf(
        DEFAULT_CONFIG,
        ALWAYS_SUSPENDING_CONFIG,
        SINGLE_THREAD_SINGLE_PARTITION_CONFIG,
        LAZY_STATE_CONFIG,
        PERSISTED_TIMERS_CONFIG)
  }

  fun resolveConfigurations(configurations: String?): List<TestConfiguration> {
    return when (configurations ?: "all") {
      "all" -> allConfigurations()
      else -> {
        var result = listOf<TestConfiguration>()
        for (configuration in configurations!!.split(',')) {
          result =
              result +
                  when (configuration) {
                    DEFAULT_CONFIG.name -> listOf(DEFAULT_CONFIG)
                    ALWAYS_SUSPENDING_CONFIG.name -> listOf(ALWAYS_SUSPENDING_CONFIG)
                    SINGLE_THREAD_SINGLE_PARTITION_CONFIG.name ->
                        listOf(SINGLE_THREAD_SINGLE_PARTITION_CONFIG)
                    LAZY_STATE_CONFIG.name -> listOf(LAZY_STATE_CONFIG)
                    PERSISTED_TIMERS_CONFIG.name -> listOf(PERSISTED_TIMERS_CONFIG)
                    else -> {
                      throw IllegalArgumentException(
                          "Unexpected configuration name $configurations")
                    }
                  }
        }
        result
      }
    }
  }
}
