// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

sealed interface ServiceDeploymentConfig

data class ContainerServiceDeploymentConfig(
    val imageName: String,
    val additionalEnvs: Map<String, String>
) : ServiceDeploymentConfig

data class LocalForwardServiceDeploymentConfig(val port: Int = 9080) : ServiceDeploymentConfig

enum class PullPolicy {
  ALWAYS,
  CACHED
}

data class RestateDeployerConfig(
    val serviceDeploymentConfig: Map<String, ServiceDeploymentConfig>,
    val restateContainerImage: String = "ghcr.io/restatedev/restate:main",
    val imagePullPolicy: PullPolicy = PullPolicy.ALWAYS,
    val restateNodes: Int = 1,
    val additionalRuntimeEnvs: Map<String, String> = mapOf(),
    val stateDirectoryMount: String? = null,
    val localIngressPort: Int? = null,
    val localAdminPort: Int? = null,
    val localNodePort: Int? = null,
    val retainAfterEnd: Boolean = false,
) {
  init {
    check(serviceDeploymentConfig.containsKey(ServiceSpec.DEFAULT_SERVICE_NAME)) {
      "When configuring the deployer, you must provide the ServiceDeploymentConfig for service '${ServiceSpec.DEFAULT_SERVICE_NAME}'"
    }
    check(restateNodes >= 1) { "Number of deployed Restate nodes must be >= 1" }
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
