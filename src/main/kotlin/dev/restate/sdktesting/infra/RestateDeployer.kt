// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

import dev.restate.admin.api.DeploymentApi
import dev.restate.admin.client.ApiClient
import dev.restate.admin.client.ApiException
import dev.restate.admin.model.RegisterDeploymentRequest
import dev.restate.admin.model.RegisterDeploymentRequestAnyOf
import dev.restate.sdktesting.infra.runtimeconfig.IngressOptions
import dev.restate.sdktesting.infra.runtimeconfig.RestateConfigSchema
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import org.apache.logging.log4j.CloseableThreadContext
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.ThreadContext
import org.junit.jupiter.api.extension.ExtensionContext
import org.rnorth.ducttape.unreliables.Unreliables
import org.testcontainers.containers.*
import org.testcontainers.images.builder.Transferable
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider
import org.testcontainers.shaded.com.github.dockerjava.core.DockerClientConfig

class RestateDeployer
private constructor(
    private val testReportDir: String,
    private val config: RestateDeployerConfig,
    private val serviceSpecs: List<ServiceSpec>,
    private val additionalContainers: Map<String, GenericContainer<*>>,
    runtimeContainerEnvs: Map<String, String>,
    copyToContainer: List<Pair<String, Transferable>>,
    configSchema: RestateConfigSchema?
) : AutoCloseable, ExtensionContext.Store.CloseableResource {

  companion object {
    internal const val RESTATE_URI_ENV = "RESTATE_URI"

    private val LOG = LogManager.getLogger(RestateDeployer::class.java)

    private val apiClient = ApiClient()

    fun builder(): Builder {
      return Builder()
    }

    fun reportDirectory(baseReportDir: Path, testClass: Class<*>): String {
      val dir = baseReportDir.resolve(testClass.simpleName).toAbsolutePath().toString()
      File(dir).mkdirs()
      return dir
    }
  }

  data class Builder(
      private var config: RestateDeployerConfig = getGlobalConfig(),
      private var serviceEndpoints: MutableList<ServiceSpec> = mutableListOf(),
      private var additionalContainers: MutableMap<String, GenericContainer<*>> = mutableMapOf(),
      private var runtimeContainerEnvs: MutableMap<String, String> = mutableMapOf(),
      private var invokerRetryPolicy: RetryPolicy? = null,
      private var configSchema: RestateConfigSchema? = null,
      private var copyToContainer: MutableList<Pair<String, Transferable>> = mutableListOf()
  ) {

    fun withServiceSpec(serviceSpec: ServiceSpec) = apply { this.serviceEndpoints.add(serviceSpec) }

    fun withServiceSpec(serviceSpecBuilder: ServiceSpec.Builder) = apply {
      this.serviceEndpoints.add(serviceSpecBuilder.build())
    }

    /** Add a container that will be added within the same network of functions and runtime. */
    fun withContainer(hostName: String, container: GenericContainer<*>) = apply {
      this.additionalContainers[hostName] = container
    }

    fun withContainer(entry: Pair<String, GenericContainer<*>>) = apply {
      this.additionalContainers[entry.first] = entry.second
    }

    fun withEnv(key: String, value: String) = apply { this.runtimeContainerEnvs[key] = value }

    fun withEnv(map: Map<String, String>) = apply { this.runtimeContainerEnvs.putAll(map) }

    fun withInvokerRetryPolicy(policy: RetryPolicy) = apply { this.invokerRetryPolicy = policy }

    fun withConfig(configSchema: RestateConfigSchema) = apply { this.configSchema = configSchema }

    fun withCopyToContainer(name: String, value: String) = apply {
      this.copyToContainer += (name to Transferable.of(value))
    }

    fun withCopyToContainer(name: String, value: ByteArray) = apply {
      this.copyToContainer += (name to Transferable.of(value))
    }

    fun build(testReportDir: String): RestateDeployer {
      val defaultLogFilters =
          mapOf(
              "restate_invoker" to "trace",
              "restate_ingress_http" to "trace",
              "restate_ingress_kafka" to "trace",
              "restate_core::partitions" to "trace",
              "restate" to "debug")
      val defaultLog =
          (listOf("info") + defaultLogFilters.map { "${it.key}=${it.value}" }).joinToString(
              separator = ",")
      val loadedRuntimeContainerEnvs =
          mapOf(
              "RUST_LOG" to (System.getenv("RUST_LOG") ?: defaultLog), "RUST_BACKTRACE" to "full") +
              System.getenv().filterKeys {
                (it.uppercase().startsWith("RESTATE_") &&
                    it.uppercase() != "RESTATE_CONTAINER_IMAGE") ||
                    it.uppercase().startsWith("RUST_")
              }

      return RestateDeployer(
          testReportDir,
          config,
          serviceEndpoints,
          additionalContainers,
          loadedRuntimeContainerEnvs +
              this.runtimeContainerEnvs +
              getGlobalConfig().additionalRuntimeEnvs +
              (invokerRetryPolicy?.toInvokerSetupEnv() ?: emptyMap()),
          copyToContainer,
          configSchema)
    }
  }

  // Infer RESTATE_URI
  private val ingressPort =
      URI(
              "http",
              configSchema?.ingress?.bindAddress ?: IngressOptions().bindAddress,
              "/",
              null,
              null)
          .port
  private val restateUri = "http://$RESTATE_RUNTIME:$ingressPort/"

  private val network = Network.newNetwork()!!
  private val serviceContainers =
      serviceSpecs
          .mapNotNull {
            val hostNameContainer = it.toHostNameContainer(config, network, restateUri)
            if (hostNameContainer != null) {
              it to hostNameContainer
            } else {
              null
            }
          }
          .associate { it.second.first to (it.first to it.second.second) }
  private val runtimeContainers: List<RestateContainer> =
      RestateContainer.bootstrapRestateCluster(
          config, network, runtimeContainerEnvs, configSchema, copyToContainer, config.restateNodes)

  private val deployedContainers: Map<String, ContainerHandle> =
      (runtimeContainers.map {
            it.hostname to ContainerHandle(it, restartWaitStrategy = { it.waitStartup() })
          } +
              serviceContainers.map { it.key to ContainerHandle(it.value.second) } +
              additionalContainers.map { it.key to ContainerHandle(it.value) })
          .associate { it }

  init {
    // Configure additional containers to be deployed within the same network where we deploy
    // everything else
    additionalContainers.forEach { (containerHost, container) ->
      container.networkAliases = ArrayList()
      container
          .withNetwork(network)
          .withNetworkAliases(containerHost)
          .withEnv(RESTATE_URI_ENV, restateUri)
          .withImagePullPolicy(config.imagePullPolicy.toTestContainersImagePullPolicy())
          .withStartupAttempts(3) // For podman
    }
  }

  fun deployAll() {
    LOG.info("Writing container logs to {}", testReportDir)

    // This generates the network down the hood
    network.id

    // Configure logging
    configureLogger(testReportDir)

    // Deploy sequentially
    deployServices()
    deployAdditionalContainers()
    deployRuntime()

    // Let's execute service discovery to register the services
    val client =
        DeploymentApi(
            ApiClient(HttpClient.newBuilder(), apiClient.objectMapper, null)
                .setHost("localhost")
                .setPort(getContainerPort(RESTATE_RUNTIME, RUNTIME_ADMIN_ENDPOINT_PORT)))
    serviceSpecs.forEach { spec -> discoverDeployment(client, spec) }

    // Log environment
    writeEnvironmentReport(testReportDir)

    LOG.info("Docker environment up and running")
  }

  private fun configureLogger(testReportDir: String) {
    serviceContainers.forEach { (_, serviceContainer) ->
      serviceContainer.second.configureLogger(testReportDir)
    }
    additionalContainers.forEach { (hostname, container) ->
      container.withLogConsumer(ContainerLogger(testReportDir, hostname))
    }
    runtimeContainers.map { it.configureLogger(testReportDir) }
  }

  private fun deployServices() {
    return serviceContainers.forEach { (serviceName, serviceContainer) ->
      serviceContainer.second.start()
      LOG.debug(
          "Started service container {} with endpoint {}",
          serviceName,
          serviceContainer.first.getEndpointUrl(config))
    }
  }

  private fun deployAdditionalContainers() {
    return additionalContainers.forEach { (containerHost, container) ->
      container.start()
      LOG.debug("Started container {} with image {}", containerHost, container.dockerImageName)
    }
  }

  private fun deployRuntime() {
    val ctx = ThreadContext.getContext()
    val executor =
        Executors.newFixedThreadPool(runtimeContainers.size) { runnable ->
          Executors.defaultThreadFactory().newThread {
            // Make sure we inject the thread context
            ThreadContext.putAll(ctx)
            runnable.run()
          }
        }

    val containerDependencies =
        serviceContainers.values.map { it.second } + additionalContainers.values

    CompletableFuture.allOf(
            *runtimeContainers
                .map { container ->
                  CompletableFuture.runAsync(
                      {
                        CloseableThreadContext.put("containerHostname", container.hostname).use {
                          if (container.hostname != RESTATE_RUNTIME) {
                            Thread.sleep(5000)
                            // Sleep first because of an internal init issue of the runtime
                          }
                          LOG.debug(
                              "Restate container '${container.hostname}' using image '${config.restateContainerImage}' is starting")
                          container.dependsOn(containerDependencies).start()
                          container.dumpConfiguration()
                          LOG.debug(
                              "Restate container '${container.hostname}' id '${container.containerId}' started and is healthy")
                        }
                      },
                      executor)
                }
                .toTypedArray())
        .get(150, TimeUnit.SECONDS)

    executor.shutdown()
  }

  private fun discoverDeployment(client: DeploymentApi, spec: ServiceSpec) {
    val url = spec.getEndpointUrl(config)
    if (spec.skipRegistration) {
      LOG.debug("Skipping registration for endpoint {}", url)
      return
    }
    val request =
        RegisterDeploymentRequest(RegisterDeploymentRequestAnyOf().uri(url.toString()).force(false))

    val response =
        Unreliables.retryUntilSuccess(20, TimeUnit.SECONDS) {
          try {
            return@retryUntilSuccess client.createDeployment(request)
          } catch (e: ApiException) {
            Thread.sleep(30)
            throw IllegalStateException(
                "Error when discovering endpoint $url, got status code ${e.code} with body: ${e.responseBody}",
                e)
          }
        }

    LOG.debug(
        """
      Successfully executed discovery for endpoint {}, registered with id {}. Discovered services: {}
    """
            .trimIndent(),
        url,
        response.id,
        response.services.map { it.name })
  }

  private fun writeEnvironmentReport(testReportDir: String) {
    val outFile = File(testReportDir, "deployer_environment.json")

    // A bit of Jackson magic to get the object mapper well configured from docker client.
    // We also need to exclude rawValues otherwise we get all the entries serialized twice.
    DockerClientConfig.getDefaultObjectMapper()
        .writer(
            SimpleFilterProvider()
                .addFilter("rawValues", SimpleBeanPropertyFilter.serializeAllExcept("rawValues")))
        .withDefaultPrettyPrinter()
        .writeValue(
            outFile,
            deployedContainers.map { it.key to it.value.container.containerInfo.rawValues }.toMap())
  }

  private fun teardownAdditionalContainers() {
    additionalContainers.forEach { (_, container) -> container.stop() }
  }

  private fun teardownServices() {
    serviceContainers.forEach { (_, container) -> container.second.stop() }
  }

  private fun teardownRuntime() {
    // The reason to terminate it manually with the docker client is to try to perform a graceful
    // shutdown,
    // to let flush the logs and spans exported as files.
    // We keep a short timeout though as we don't want to influence too much the teardown time of
    // the tests.
    runtimeContainers.forEach {
      if (it.containerId == null) {
        LOG.warn(
            "During shutdown container ${it.hostname} has no container id, thus it's not running already.")
        return@forEach
      }
      try {
        it.dockerClient
            .stopContainerCmd(it.containerId)
            .withTimeout(1) // This is seconds
            .exec()
      } catch (e: Exception) {
        LOG.warn("Error when trying to send stop container signal to ${it.containerId}", e)
      }
    }
    runtimeContainers.forEach { it.stop() }
  }

  private fun teardownAll() {
    // Before teardown, we optimistically try to dump some info about the invocations to files
    if (runtimeContainers[0].isRunning) {
      dumpSQLTable("sys_invocation")
      dumpSQLTable("sys_journal")
      dumpSQLTable("state")
    }

    if (config.retainAfterEnd) {
      LOG.info("Press a button to cleanup the test environment. Deployed network: {}", network.id)
      System.`in`.read()
      return
    }
    teardownRuntime()
    teardownAdditionalContainers()
    teardownServices()
    network.close()
    LOG.info("Docker environment cleaned up")
  }

  internal fun dumpSQLTable(tableName: String) {
    try {
      val client = HttpClient.newHttpClient()
      val request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "http://localhost:${getContainerPort(RESTATE_RUNTIME, RUNTIME_ADMIN_ENDPOINT_PORT)}/query"))
              .header("accept", "application/json")
              .header("content-type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      """{"query": "SELECT * FROM ${tableName}"}"""))
              .timeout(10.seconds.toJavaDuration())
              .build()

      val response =
          client.send(
              request, BodyHandlers.ofFile(Paths.get(testReportDir, "${tableName}_dump.json")))

      LOG.info("Dumped SQL table $tableName to ${response.body()}")
    } catch (e: Throwable) {
      LOG.warn("Error when trying to dump SQL table $tableName", e)
    }
  }

  internal fun getContainerPort(hostName: String, port: Int): Int {
    return deployedContainers[hostName]?.getMappedPort(port)
        ?: throw java.lang.IllegalStateException(
            "Requested port for container $hostName, but the container or the port was not found")
  }

  fun getContainerHandle(hostName: String): ContainerHandle {
    if (!deployedContainers.containsKey(hostName)) {
      // If it's service spec with local forward, then this is expected
      if (serviceSpecs
          .find { it.name == hostName }
          ?.let {
            config.getServiceDeploymentConfig(it.name) is LocalForwardServiceDeploymentConfig
          } == true) {
        throw java.lang.IllegalArgumentException(
            "This test cannot run in debug mode, because it requires to manually start/stop the service container '$hostName'. Run the test without run mode.")
      }
      throw IllegalArgumentException(
          "Cannot find container $hostName. Most likely, there is a bug in the test code.")
    }
    return deployedContainers[hostName]!!
  }

  override fun close() {
    teardownAll()
  }
}
