// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import dev.restate.sdk.client.Client
import dev.restate.sdktesting.contracts.CounterClient
import dev.restate.sdktesting.contracts.CounterDefinitions
import dev.restate.sdktesting.infra.InjectClient
import dev.restate.sdktesting.infra.RestateDeployerExtension
import dev.restate.sdktesting.infra.ServiceSpec
import java.util.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

class ProxyRequestSigning {

  companion object {
    const val E2E_REQUEST_SIGNING_ENV = "E2E_REQUEST_SIGNING"
    const val PRIVATE_KEY =
        """
-----BEGIN PRIVATE KEY-----
MC4CAQAwBQYDK2VwBCIEIHsQRVQ+AZX9/Yy1b0Zw+OA+bb7xDxGsAd5kB45jZhoc
-----END PRIVATE KEY-----
"""
    const val SIGNING_KEY = "publickeyv1_ChjENKeMvCtRnqG2mrBK1HmPKufgFUc98K8B3ononQvp"

    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withCopyToContainer("/a.pem", PRIVATE_KEY)
      withEnv("RESTATE_REQUEST_IDENTITY_PRIVATE_KEY_PEM_FILE", "/a.pem")
      withServiceSpec(
          ServiceSpec.builder("service-with-request-signing")
              .withServices(CounterDefinitions.SERVICE_NAME)
              .withEnv(E2E_REQUEST_SIGNING_ENV, SIGNING_KEY)
              .build())
    }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun requestSigningPass(@InjectClient ingressClient: Client) = runTest {
    val counterName = UUID.randomUUID().toString()
    val client = CounterClient.fromClient(ingressClient, counterName)

    client.add(1, idempotentCallOptions())
    assertThat(client.get(idempotentCallOptions())).isEqualTo(1)
  }
}
