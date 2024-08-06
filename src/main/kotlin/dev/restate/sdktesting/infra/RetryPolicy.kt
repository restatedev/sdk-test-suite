// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

// Perhaps at some point we could autogenerate these from the openapi doc and also remove the need
// to manually implement these serialization routines
sealed class RetryPolicy {

  abstract fun toInvokerSetupEnv(): Map<String, String>

  object None : RetryPolicy() {
    override fun toInvokerSetupEnv(): Map<String, String> {
      return mapOf("RESTATE_WORKER__INVOKER__RETRY_POLICY__TYPE" to "none")
    }
  }

  class FixedDelay(private val interval: String, private val maxAttempts: Int) : RetryPolicy() {
    override fun toInvokerSetupEnv(): Map<String, String> {
      return mapOf(
          "RESTATE_WORKER__INVOKER__RETRY_POLICY__TYPE" to "fixed-delay",
          "RESTATE_WORKER__INVOKER__RETRY_POLICY__INTERVAL" to interval,
          "RESTATE_WORKER__INVOKER__RETRY_POLICY__MAX_ATTEMPTS" to maxAttempts.toString())
    }
  }
}
