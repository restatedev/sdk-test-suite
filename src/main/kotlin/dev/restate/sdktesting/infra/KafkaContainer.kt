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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import org.testcontainers.images.builder.Transferable
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

/**
 * This class overrides the testcontainers [org.testcontainers.kafka.KafkaContainer] to introduce
 * two additional behaviours:
 * * Fix the `advertised.listeners` override with the correct hostname (maybe we can upstream this
 *   fix?)
 * * Create topics after the container is started
 */
class KafkaContainer :
    KafkaContainer(
        DockerImageName.parse("docker.io/apache/kafka-native:4.1.1")
            .asCompatibleSubstituteFor("apache/kafka")) {

  companion object {
    const val KAFKA_NETWORK_PORT = 9092
    const val KAFKA_EXTERNAL_PORT = 9094
    const val STARTER_SCRIPT = "/tmp/testcontainers_start.sh"
  }

  init {
    // Make sure we have auto.create.topics.enable as true
    withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
    withStartupTimeout(2.minutes.toJavaDuration())

    // Set network alias so it's accessible as kafka:9092 within docker network
    withNetworkAliases("kafka")

    // Expose the external port for host access
    addExposedPort(KAFKA_EXTERNAL_PORT)
  }

  override fun containerIsStarting(containerInfo: InspectContainerResponse) {
    // Don't call super - we're doing our own thing

    // Get the dynamically mapped external port
    val externalPort = getMappedPort(KAFKA_EXTERNAL_PORT)

    // Build advertised listeners:
    // - INTERNAL: for docker network access (kafka:9092)
    // - EXTERNAL: for host access (localhost:random-port)
    // - BROKER: for inter-broker communication
    val advertisedListeners =
        listOf(
                "INTERNAL://kafka:9092",
                "EXTERNAL://${host}:${externalPort}",
                "BROKER://${containerInfo.config.hostName}:9093")
            .joinToString(",")

    // Create startup script that exports the advertised listeners
    // INTERNAL binds to 9092, EXTERNAL binds to 9094 (different ports!)
    val command =
        """
      #!/bin/bash
      export KAFKA_ADVERTISED_LISTENERS=$advertisedListeners
      export KAFKA_LISTENERS=INTERNAL://0.0.0.0:9092,EXTERNAL://0.0.0.0:9094,BROKER://0.0.0.0:9093,CONTROLLER://0.0.0.0:9095
      export KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,BROKER:PLAINTEXT,CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      export KAFKA_INTER_BROKER_LISTENER_NAME=BROKER
      export KAFKA_CONTROLLER_QUORUM_VOTERS=1@${containerInfo.config.hostName}:9095
      /etc/kafka/docker/run
    """
            .trimIndent()

    copyFileToContainer(Transferable.of(command, 0x1ff), STARTER_SCRIPT)
  }
}
