// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import dev.restate.admin.api.SubscriptionApi
import dev.restate.admin.client.ApiClient
import dev.restate.admin.model.CreateSubscriptionRequest
import dev.restate.sdk.client.Client
import dev.restate.sdktesting.contracts.CounterClient
import dev.restate.sdktesting.contracts.CounterDefinitions
import dev.restate.sdktesting.contracts.EventHandlerDefinitions
import dev.restate.sdktesting.contracts.TestEvent
import dev.restate.sdktesting.infra.*
import dev.restate.sdktesting.infra.runtimeconfig.IngressOptions
import dev.restate.sdktesting.infra.runtimeconfig.KafkaClusterOptions
import dev.restate.sdktesting.infra.runtimeconfig.RestateConfigSchema
import java.net.URL
import java.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

private const val COUNTER_TOPIC = "counter"
private const val EVENT_HANDLER_TOPIC = "event-handler"

private fun kafkaClusterOptions(): RestateConfigSchema {
  return RestateConfigSchema()
      .withIngress(
          IngressOptions()
              .withKafkaClusters(
                  listOf(
                      KafkaClusterOptions()
                          .withName("my-cluster")
                          .withBrokers(listOf("PLAINTEXT://kafka:9092")))))
}

class KafkaIngressTest {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(EventHandlerDefinitions.SERVICE_NAME, CounterDefinitions.SERVICE_NAME))
      withContainer("kafka", KafkaContainer(COUNTER_TOPIC, EVENT_HANDLER_TOPIC))
      withConfig(kafkaClusterOptions())
    }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun handleEventInCounterService(
      @InjectMetaURL metaURL: URL,
      @InjectContainerPort(hostName = "kafka", port = KafkaContainer.EXTERNAL_PORT) kafkaPort: Int,
      @InjectClient ingressClient: Client
  ) = runTest {
    val counter = UUID.randomUUID().toString()

    // Create subscription
    val subscriptionsClient =
        SubscriptionApi(ApiClient().setHost(metaURL.host).setPort(metaURL.port))
    subscriptionsClient.createSubscription(
        CreateSubscriptionRequest()
            .source("kafka://my-cluster/$COUNTER_TOPIC")
            .sink("service://${CounterDefinitions.SERVICE_NAME}/add")
            .options(mapOf("auto.offset.reset" to "earliest")))

    // Produce message to kafka
    produceMessageToKafka(
        "PLAINTEXT://localhost:$kafkaPort",
        COUNTER_TOPIC,
        listOf(counter to "1", counter to "2", counter to "3"))

    // Now wait for the update to be visible
    await untilCallTo
        {
          runBlocking { CounterClient.fromClient(ingressClient, counter).get() }
        } matches
        { num ->
          num!! == 6L
        }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun handleEventInEventHandler(
      @InjectMetaURL metaURL: URL,
      @InjectContainerPort(hostName = "kafka", port = KafkaContainer.EXTERNAL_PORT) kafkaPort: Int,
      @InjectClient ingressClient: Client
  ) = runTest {
    val counter = UUID.randomUUID().toString()

    // Create subscription
    val subscriptionsClient =
        SubscriptionApi(ApiClient().setHost(metaURL.host).setPort(metaURL.port))
    subscriptionsClient.createSubscription(
        CreateSubscriptionRequest()
            .source("kafka://my-cluster/$EVENT_HANDLER_TOPIC")
            .sink("service://${EventHandlerDefinitions.SERVICE_NAME}/handle")
            .options(mapOf("auto.offset.reset" to "earliest")))

    // Produce message to kafka
    produceMessageToKafka(
        "PLAINTEXT://localhost:$kafkaPort",
        EVENT_HANDLER_TOPIC,
        listOf(
            null to Json.encodeToString(TestEvent(counter, 1)),
            null to Json.encodeToString(TestEvent(counter, 2)),
            null to Json.encodeToString(TestEvent(counter, 3))))

    // Now wait for the update to be visible
    await untilCallTo
        {
          runBlocking { CounterClient.fromClient(ingressClient, counter).get() }
        } matches
        { num ->
          num!! == 6L
        }
  }
}

private fun produceMessageToKafka(
    bootstrapServer: String,
    topic: String,
    values: List<Pair<String?, String>>
) {
  val props = Properties()
  props["bootstrap.servers"] = bootstrapServer
  props["key.serializer"] = "org.apache.kafka.common.serialization.StringSerializer"
  props["value.serializer"] = "org.apache.kafka.common.serialization.StringSerializer"

  val producer: Producer<String, String> = KafkaProducer(props)
  for (value in values) {
    producer.send(ProducerRecord(topic, value.first, value.second))
  }
  producer.close()
}
