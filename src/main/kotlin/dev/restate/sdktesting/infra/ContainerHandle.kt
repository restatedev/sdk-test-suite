// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

import com.github.dockerjava.api.DockerClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.apache.logging.log4j.LogManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy
import org.testcontainers.utility.LogUtils

/** Handle to interact with deployed containers */
class ContainerHandle
internal constructor(
    internal val container: GenericContainer<*>,
    private val afterRestartWaitStrategy: () -> Unit = {},
) {

  private val logger = LogManager.getLogger(ContainerHandle::class.java)

  suspend fun terminateAndRestart() {
    logger.info(
        "Going to terminate and restart the container {} with hostnames {}.",
        container.containerName,
        container.networkAliases.joinToString())
    retryDockerClientCommand { dockerClient, containerId ->
      dockerClient.restartContainerCmd(containerId).exec()
    }

    postStart()
  }

  suspend fun killAndRestart() {
    logger.info(
        "Going to kill and restart the container {} with hostnames {}.",
        container.containerName,
        container.networkAliases.joinToString())
    retryDockerClientCommand { dockerClient, containerId ->
      dockerClient.restartContainerCmd(containerId).withSignal("SIGKILL").withTimeout(0).exec()
    }

    postStart()
  }

  suspend fun terminate() {
    terminate(10.seconds)
  }

  suspend fun terminate(timeout: Duration) {
    logger.info(
        "Going to terminate the container {} with hostnames {}.",
        container.containerName,
        container.networkAliases.joinToString())
    retryDockerClientCommand { dockerClient, containerId ->
      dockerClient.stopContainerCmd(containerId).withTimeout(timeout.inWholeSeconds.toInt()).exec()
    }
  }

  suspend fun kill() {
    logger.info(
        "Going to kill the container {} with hostnames {}.",
        container.containerName,
        container.networkAliases.joinToString())
    retryDockerClientCommand { dockerClient, containerId ->
      dockerClient.killContainerCmd(containerId).exec()
    }
  }

  suspend fun start() {
    if (!isRunning()) {
      logger.info(
          "Going to start the container {} with hostnames {}.",
          container.containerName,
          container.networkAliases.joinToString())
      retryDockerClientCommand { dockerClient, containerId ->
        dockerClient.startContainerCmd(containerId).exec()
      }

      postStart()
    }
  }

  suspend fun isRunning(): Boolean {
    return retryDockerClientCommand { dockerClient, containerId ->
          dockerClient.inspectContainerCmd(containerId).exec()
        }
        .state
        .running ?: false
  }

  fun getMappedPort(port: Int): Int? {
    return container.getMappedPort(port)
  }

  private fun postStart() {
    logger.debug("Started post start checks for container {}.", container.containerName)

    // Wait for running start
    IsRunningStartupCheckStrategy()
        .waitUntilStartupSuccessful(container.dockerClient, container.containerId)

    // We need to start following again, as stopping also stops following logs
    container.logConsumers.forEach {
      LogUtils.followOutput(container.dockerClient, container.containerId, it)
    }

    // Additional wait strategy for ports
    afterRestartWaitStrategy()

    logger.info("Container {} started and passed all the checks.", container.containerName)
  }

  private suspend fun <T> retryDockerClientCommand(fn: (DockerClient, String) -> T): T {
    val client = container.dockerClient
    val containerId = container.containerId

    // In most of the cases, these retries are not necessary.
    // In Podman environments though, we might hit the case where we need it due to how
    // podman.socket works.
    var lastException: Throwable? = null
    for (i in 0..10) {
      try {
        return fn(client, containerId)
      } catch (exception: Throwable) {
        delay(20.milliseconds)
        logger.warn(
            "Error when trying to execute docker command: {}. This might be a problem with the local docker daemon.",
            exception.message)
        lastException = exception
      }
    }
    throw lastException!!
  }
}
