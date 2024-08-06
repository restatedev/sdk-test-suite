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
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.ThreadContext
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.fail
import org.testcontainers.containers.*
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider
import org.testcontainers.shaded.com.github.dockerjava.core.DockerClientConfig

class RestateDeployer
private constructor(
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
    private val startContainersThreadPoolExecutor = Executors.newCachedThreadPool()

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

    fun build(): RestateDeployer {
      val defaultLogFilters =
          mapOf(
              "restate_invoker" to "trace",
              "restate_ingress_kafka" to "trace",
              "restate_worker::partition::services::non_deterministic::remote_context" to "trace",
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

  private val network = Network.newNetwork()
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
  // TODO replace toxiproxy with a socat container
  private val proxyContainer =
      ProxyContainer(
          ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
              .withImagePullPolicy(config.imagePullPolicy.toTestContainersImagePullPolicy()))
  private val runtimeContainer =
      RestateContainer(config, network, runtimeContainerEnvs, configSchema, copyToContainer)
  private val deployedContainers: Map<String, ContainerHandle> =
      mapOf(
          RESTATE_RUNTIME to
              ContainerHandle(
                  runtimeContainer,
                  { proxyContainer.getMappedPort(RESTATE_RUNTIME, it) },
                  {
                    Wait.forListeningPort().waitUntilReady(NotCachedContainerInfo(runtimeContainer))
                    waitRuntimeHealthy()
                  })) +
          serviceContainers.map { it.key to ContainerHandle(it.value.second) } +
          additionalContainers.map { it.key to ContainerHandle(it.value) }

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

  fun deployAll(testReportDir: String) {
    LOG.info("Writing container logs to {}", testReportDir)

    // This generates the network down the hood
    network.id

    // Configure logging
    configureLogger(testReportDir)

    if (config.deployInParallel) {
      // Deploy all containers in parallel
      val startFutures =
          deployServicesConcurrently() +
              deployAdditionalContainersConcurrently() +
              listOf(deployRuntime()) +
              listOf(deployProxy(testReportDir))
      CompletableFuture.allOf(*startFutures.toTypedArray()).join()
    } else {
      // Deploy sequentially
      deployServicesSequentially()
      deployAdditionalContainersSequentially()
      deployRuntime().join()
      deployProxy(testReportDir).join()
    }

    // Configure proxy
    configureProxy()

    // Let's execute service discovery to register the services
    waitRuntimeAdminHealthy()
    val client =
        DeploymentApi(
            ApiClient(HttpClient.newBuilder(), apiClient.objectMapper, null)
                .setHost("localhost")
                .setPort(getContainerPort(RESTATE_RUNTIME, RUNTIME_META_ENDPOINT_PORT)))
    serviceSpecs.forEach { spec -> discoverDeployment(client, spec) }

    // Log environment
    writeEnvironmentReport(testReportDir)

    // Wait runtime ingress healthy
    waitRuntimeIngressHealthy()
  }

  private fun configureLogger(testReportDir: String) {
    serviceContainers.forEach { (_, serviceContainer) ->
      serviceContainer.second.configureLogger(testReportDir)
    }
    additionalContainers.forEach { (hostname, container) ->
      container.withLogConsumer(ContainerLogger(testReportDir, hostname))
    }
    runtimeContainer.configureLogger(testReportDir)
  }

  private fun deployServicesConcurrently(): List<CompletableFuture<*>> {
    return serviceContainers.map { (serviceName, serviceContainer) ->
      runOnStartupThreadPool {
        serviceContainer.second.start()
        LOG.debug(
            "Started service container {} with endpoint {}",
            serviceName,
            serviceContainer.first.getEndpointUrl(config))
      }
    }
  }

  private fun deployServicesSequentially() {
    return serviceContainers.forEach { (serviceName, serviceContainer) ->
      serviceContainer.second.start()
      LOG.debug(
          "Started service container {} with endpoint {}",
          serviceName,
          serviceContainer.first.getEndpointUrl(config))
    }
  }

  private fun deployAdditionalContainersConcurrently(): List<CompletableFuture<*>> {
    return additionalContainers.map { (containerHost, container) ->
      runOnStartupThreadPool {
        container.start()
        LOG.debug("Started container {} with image {}", containerHost, container.dockerImageName)
      }
    }
  }

  private fun deployAdditionalContainersSequentially() {
    return additionalContainers.forEach { (containerHost, container) ->
      container.start()
      LOG.debug("Started container {} with image {}", containerHost, container.dockerImageName)
    }
  }

  private fun deployRuntime(): CompletableFuture<*> {
    return runOnStartupThreadPool {
      runtimeContainer.start()
      LOG.debug("Restate runtime started. Container id {}", runtimeContainer.containerId)
    }
  }

  private fun deployProxy(testReportDir: String): CompletableFuture<*> {
    return runOnStartupThreadPool { proxyContainer.start(network, testReportDir) }
  }

  private fun configureProxy() {
    // We use an external proxy to access from the test code to the restate container in order to
    // retain the tcp port binding across restarts.
    // Proxy runtime ports
    val adminPort = proxyContainer.mapPort(RESTATE_RUNTIME, RUNTIME_META_ENDPOINT_PORT)
    val ingressPort = proxyContainer.mapPort(RESTATE_RUNTIME, RUNTIME_INGRESS_ENDPOINT_PORT)

    LOG.debug("Toxiproxy started. Ingress port: {}. Admin API port: {}", ingressPort, adminPort)
  }

  private fun waitRuntimeAdminHealthy() {
    proxyContainer.waitHttp(
        Wait.forHttp("/health"),
        RESTATE_RUNTIME,
        RUNTIME_META_ENDPOINT_PORT,
    )
    LOG.debug("Runtime Admin healthy")
  }

  private fun waitRuntimeIngressHealthy() {
    proxyContainer.waitHttp(
        Wait.forHttp("/restate/health"),
        RESTATE_RUNTIME,
        RUNTIME_INGRESS_ENDPOINT_PORT,
    )
    LOG.debug("Runtime Ingress healthy")
  }

  private fun waitRuntimeHealthy() {
    waitRuntimeAdminHealthy()
    waitRuntimeIngressHealthy()
  }

  fun discoverDeployment(client: DeploymentApi, spec: ServiceSpec) {
    val url = spec.getEndpointUrl(config)
    if (spec.skipRegistration) {
      LOG.debug("Skipping registration for endpoint {}", url)
      return
    }

    val request =
        RegisterDeploymentRequest(RegisterDeploymentRequestAnyOf().uri(url.toString()).force(false))
    try {
      val response = client.createDeployment(request)
      LOG.debug("Successfully executed discovery for endpoint {}. Result: {}", url, response)
    } catch (e: ApiException) {
      fail(
          "Error when discovering endpoint $url, got status code ${e.code} with body: ${e.responseBody}",
          e)
    }
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
    runtimeContainer.dockerClient
        .stopContainerCmd(runtimeContainer.containerId)
        .withTimeout(1) // This is seconds
        .exec()
    runtimeContainer.stop()
  }

  private fun teardownProxy() {
    proxyContainer.stop()
  }

  private fun teardownAll() {
    teardownRuntime()
    teardownAdditionalContainers()
    teardownServices()
    teardownProxy()
    network!!.close()
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

  private fun runOnStartupThreadPool(fn: () -> Unit): CompletableFuture<Void> {
    val contextMap: Map<String, String> = ThreadContext.getImmutableContext()
    val contextStackTop: String = ThreadContext.peek()

    return CompletableFuture.runAsync(
        {
          ThreadContext.putAll(contextMap)
          ThreadContext.push(contextStackTop)
          try {
            fn()
          } finally {
            ThreadContext.pop()
            ThreadContext.clearMap()
          }
        },
        startContainersThreadPoolExecutor)
  }

  override fun close() {
    teardownAll()
  }
}
