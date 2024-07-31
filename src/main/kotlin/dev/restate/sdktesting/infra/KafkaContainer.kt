// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

import com.github.dockerjava.api.command.InspectContainerResponse
import org.testcontainers.utility.DockerImageName

/**
 * This class overrides the testcontainers [org.testcontainers.containers.KafkaContainer] to
 * introduce two additional behaviours:
 * * Fix the `advertised.listeners` override with the correct hostname (maybe we can upstream this
 *   fix?)
 * * Create topics after the container is started
 */
class KafkaContainer(private vararg val topics: String) :
    org.testcontainers.containers.KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.1.0-1-ubi8")) {

  companion object {
    const val EXTERNAL_PORT = KAFKA_PORT
  }

  init {
    // Make sure we have auto.create.topics.enable as true
    withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
  }

  // This is copied and pasted from KafkaContainer original class to add the listeners from the
  // network aliases and remove the brokerAdvertisedListener
  override fun containerIsStarted(containerInfo: InspectContainerResponse) {
    val currentBrokerAdvertisedAddress = brokerAdvertisedListener(containerInfo)

    // Create topics first
    topics.forEach { topic ->
      execInContainer(
          "kafka-topics",
          "--create",
          "--topic",
          topic,
          "--bootstrap-server",
          currentBrokerAdvertisedAddress)
    }

    val listenerList = networkAliases.map { "BROKER://$it:9092" } + bootstrapServers
    val result =
        execInContainer(
            "kafka-configs",
            "--alter",
            "--bootstrap-server",
            currentBrokerAdvertisedAddress,
            "--entity-type",
            "brokers",
            "--entity-name",
            envMap["KAFKA_BROKER_ID"],
            "--add-config",
            "advertised.listeners=[${listenerList.joinToString(separator = ",")}]")
    check(result.exitCode == 0) { result.toString() }
  }
}
