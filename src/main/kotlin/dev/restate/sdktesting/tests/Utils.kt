// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate e2e tests,
// which are released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/e2e/blob/main/LICENSE

package dev.restate.sdktesting.tests

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import org.assertj.core.api.Assertions.assertThat

object Utils {

  private val objMapper = ObjectMapper()
  private val httpClient = HttpClient.newHttpClient()

  fun jacksonBodyHandler(): HttpResponse.BodyHandler<JsonNode> {
    return HttpResponse.BodyHandler {
      HttpResponse.BodySubscribers.mapping(
          HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), objMapper::readTree)
    }
  }

  fun jacksonBodyPublisher(value: Any): HttpRequest.BodyPublisher {
    return HttpRequest.BodyPublishers.ofString(objMapper.writeValueAsString(value))
  }

  fun postJsonRequest(uri: String, reqBody: Any): HttpResponse<JsonNode> {
    val req =
        HttpRequest.newBuilder(URI.create(uri))
            .headers("Content-Type", "application/json")
            .POST(jacksonBodyPublisher(reqBody))
            .build()
    return httpClient.send(req, jacksonBodyHandler())
  }

  fun writeValueAsStringUsingJackson(value: Any?): String {
    return objMapper.writeValueAsString(value)
  }

  fun doJsonRequestToService(
      restateEndpoint: String,
      service: String,
      method: String,
      reqBody: Any
  ): JsonNode {
    val res = postJsonRequest("${restateEndpoint}${service}/${method}", reqBody)
    assertThat(res.statusCode()).isEqualTo(200)
    assertThat(res.headers().firstValue("content-type"))
        .get()
        .asString()
        .contains("application/json")
    return res.body()
  }
}
