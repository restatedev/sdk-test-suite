// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

import dev.restate.client.Client
import java.net.URI
import java.nio.file.Path
import org.junit.jupiter.api.extension.*
import org.junit.jupiter.api.extension.ExtensionContext.Namespace

abstract class BaseRestateDeployerExtension : ParameterResolver {

  companion object {
    internal val NAMESPACE = Namespace.create(BaseRestateDeployerExtension::class.java)
    internal const val DEPLOYER_KEY = "Deployer"

    const val REPORT_DIR_PROPERTY_NAME = "restatedeployer.reportdir"
  }

  override fun supportsParameter(
      parameterContext: ParameterContext,
      extensionContext: ExtensionContext
  ): Boolean {
    return (parameterContext.isAnnotated(InjectClient::class.java) &&
        Client::class.java.isAssignableFrom(parameterContext.parameter.type)) ||
        (parameterContext.isAnnotated(InjectContainerPort::class.java) &&
            Int::class.java.isAssignableFrom(parameterContext.parameter.type)) ||
        (parameterContext.isAnnotated(InjectIngressURI::class.java) &&
            URI::class.java.isAssignableFrom(parameterContext.parameter.type)) ||
        (parameterContext.isAnnotated(InjectAdminURI::class.java) &&
            URI::class.java.isAssignableFrom(parameterContext.parameter.type)) ||
        (parameterContext.isAnnotated(InjectContainerHandle::class.java) &&
            ContainerHandle::class.java.isAssignableFrom(parameterContext.parameter.type))
  }

  override fun resolveParameter(
      parameterContext: ParameterContext,
      extensionContext: ExtensionContext
  ): Any? {
    return if (parameterContext.isAnnotated(InjectClient::class.java)) {
      resolveIngressClient(extensionContext)
    } else if (parameterContext.isAnnotated(InjectContainerPort::class.java)) {
      resolveContainerAddress(parameterContext, extensionContext)
    } else if (parameterContext.isAnnotated(InjectIngressURI::class.java)) {
      resolveIngressURI(extensionContext)
    } else if (parameterContext.isAnnotated(InjectAdminURI::class.java)) {
      resolveAdminURI(extensionContext)
    } else if (parameterContext.isAnnotated(InjectContainerHandle::class.java)) {
      resolveContainerHandle(parameterContext, extensionContext)
    } else {
      null
    }
  }

  private fun resolveIngressClient(extensionContext: ExtensionContext): Client {
    return Client.connect(resolveIngressURI(extensionContext).toString())
  }

  private fun resolveContainerAddress(
      parameterContext: ParameterContext,
      extensionContext: ExtensionContext
  ): Any {
    val annotation = parameterContext.findAnnotation(InjectContainerPort::class.java).get()

    return getDeployer(extensionContext).getContainerPort(annotation.hostName, annotation.port)
  }

  private fun resolveIngressURI(extensionContext: ExtensionContext): URI {
    return URI.create(
        "http://127.0.0.1:${ getDeployer(extensionContext)
      .getContainerPort(RESTATE_RUNTIME, RUNTIME_INGRESS_ENDPOINT_PORT)}/")
  }

  private fun resolveAdminURI(extensionContext: ExtensionContext): URI {
    return URI.create(
        "http://127.0.0.1:${ getDeployer(extensionContext)
      .getContainerPort(RESTATE_RUNTIME, RUNTIME_ADMIN_ENDPOINT_PORT)}/")
  }

  private fun resolveContainerHandle(
      parameterContext: ParameterContext,
      extensionContext: ExtensionContext
  ): Any {
    val annotation = parameterContext.findAnnotation(InjectContainerHandle::class.java).get()

    return getDeployer(extensionContext).getContainerHandle(annotation.hostName)
  }

  private fun getDeployer(extensionContext: ExtensionContext): RestateDeployer {
    return (extensionContext.getStore(NAMESPACE).get(DEPLOYER_KEY) as RestateDeployer?)!!
  }

  internal fun getReportPath(extensionContext: ExtensionContext): Path {
    return Path.of(extensionContext.getConfigurationParameter(REPORT_DIR_PROPERTY_NAME).get())
  }
}
