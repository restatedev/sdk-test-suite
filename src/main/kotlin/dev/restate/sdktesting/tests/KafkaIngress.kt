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
import dev.restate.client.Client
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.*
import dev.restate.sdktesting.infra.runtimeconfig.IngressOptions
import dev.restate.sdktesting.infra.runtimeconfig.KafkaClusterOptions
import dev.restate.sdktesting.infra.runtimeconfig.RestateConfigSchema
import java.net.URI
import java.util.*
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.logging.log4j.LogManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
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

class KafkaIngress {

  companion object {
    val LOG = LogManager.getLogger(KafkaIngress::class.java)

    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.defaultBuilder()
              .withServices(
                  ProxyHandlers.Metadata.SERVICE_NAME, CounterHandlers.Metadata.SERVICE_NAME))
      withContainer("kafka", KafkaContainer(COUNTER_TOPIC, EVENT_HANDLER_TOPIC))
      withConfig(kafkaClusterOptions())
    }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun handleEventInCounterService(
      @InjectAdminURI adminURI: URI,
      @InjectContainerPort(hostName = "kafka", port = KafkaContainer.EXTERNAL_PORT) kafkaPort: Int,
      @InjectClient ingressClient: Client
  ) = runTest {
    val counter = UUID.randomUUID().toString()

    // Create subscription
    val subscriptionsClient =
        SubscriptionApi(ApiClient().setHost(adminURI.host).setPort(adminURI.port))
    subscriptionsClient.createSubscription(
        CreateSubscriptionRequest()
            .source("kafka://my-cluster/$COUNTER_TOPIC")
            .sink("service://${CounterHandlers.Metadata.SERVICE_NAME}/add")
            .options(mapOf("auto.offset.reset" to "earliest")))

    // Produce message to kafka
    produceMessageToKafka(
        "PLAINTEXT://localhost:$kafkaPort",
        COUNTER_TOPIC,
        listOf(counter to "1", counter to "2", counter to "3"))

    await withAlias
        "Updates from Kafka are visible in the counter" untilAsserted
        {
          assertThat(CounterClient.fromClient(ingressClient, counter).get()).isEqualTo(6L)
        }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  fun handleEventInEventHandler(
      @InjectAdminURI adminURI: URI,
      @InjectContainerPort(hostName = "kafka", port = KafkaContainer.EXTERNAL_PORT) kafkaPort: Int,
      @InjectClient ingressClient: Client
  ) = runTest {
    val counter = UUID.randomUUID().toString()

    // Create subscription
    val subscriptionsClient =
        SubscriptionApi(ApiClient().setHost(adminURI.host).setPort(adminURI.port))
    subscriptionsClient.createSubscription(
        CreateSubscriptionRequest()
            .source("kafka://my-cluster/$EVENT_HANDLER_TOPIC")
            .sink("service://${ProxyHandlers.Metadata.SERVICE_NAME}/oneWayCall")
            .options(mapOf("auto.offset.reset" to "earliest")))

    // Produce message to kafka
    produceMessageToKafka(
        "PLAINTEXT://localhost:$kafkaPort",
        EVENT_HANDLER_TOPIC,
        listOf(
            null to
                Json.encodeToString(
                    ProxyRequest(
                        CounterHandlers.Metadata.SERVICE_NAME,
                        counter,
                        "add",
                        Json.encodeToString(1).encodeToByteArray())),
            null to
                Json.encodeToString(
                    ProxyRequest(
                        CounterHandlers.Metadata.SERVICE_NAME,
                        counter,
                        "add",
                        Json.encodeToString(2).encodeToByteArray())),
            null to
                Json.encodeToString(
                    ProxyRequest(
                        CounterHandlers.Metadata.SERVICE_NAME,
                        counter,
                        "add",
                        Json.encodeToString(3).encodeToByteArray())),
        ))

    await withAlias
        "Updates from Kafka are visible in the counter" untilAsserted
        {
          assertThat(CounterClient.fromClient(ingressClient, counter).get()).isEqualTo(6L)
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
    val metadata = producer.send(ProducerRecord(topic, value.first, value.second)).get()
    KafkaIngress.LOG.info(
        "Produced record with key ${value.first ?: "null"} to topic ${metadata.topic()} at offset ${metadata.offset()}")
  }
  producer.close()
}
