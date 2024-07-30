package dev.restate.sdktesting.infra

sealed interface ServiceDeploymentConfig

data class ContainerServiceDeploymentConfig(val imageName: String) : ServiceDeploymentConfig

data class LocalForwardServiceDeploymentConfig(val port: Int = 9080) : ServiceDeploymentConfig

enum class PullPolicy {
  ALWAYS,
  CACHED
}

data class RestateDeployerConfig(
    val serviceDeploymentConfig: Map<String, ServiceDeploymentConfig>,
    val restateContainerImage: String = "ghcr.io/restatedev/restate:main",
    val imagePullPolicy: PullPolicy = PullPolicy.ALWAYS,
    val additionalRuntimeEnvs: Map<String, String> = mapOf(),
    val stateDirectoryMount: String? = null,
    val localIngressPort: Int? = null,
    val localAdminPort: Int? = null
) {
  init {
    check(serviceDeploymentConfig.containsKey(ServiceSpec.DEFAULT_SERVICE_NAME)) {
      "When configuring the deployer, you must provide the ServiceDeploymentConfig for service '${ServiceSpec.DEFAULT_SERVICE_NAME}'"
    }
  }

  fun getServiceDeploymentConfig(name: String): ServiceDeploymentConfig {
    return serviceDeploymentConfig.get(name)
        ?: serviceDeploymentConfig.get(ServiceSpec.DEFAULT_SERVICE_NAME)!!
  }
}

@Volatile private lateinit var CONFIG: RestateDeployerConfig

fun registerGlobalConfig(conf: RestateDeployerConfig) {
  CONFIG = conf
}

fun getGlobalConfig(): RestateDeployerConfig = CONFIG
