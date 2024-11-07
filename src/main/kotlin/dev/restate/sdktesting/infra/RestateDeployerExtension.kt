// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

import org.junit.jupiter.api.extension.*

class RestateDeployerExtension(private val deployerFactory: RestateDeployer.Builder.() -> Unit) :
    BeforeAllCallback, BaseRestateDeployerExtension() {

  override fun beforeAll(context: ExtensionContext) {
    val builder = RestateDeployer.builder()
    deployerFactory.invoke(builder)
    val deployer =
        builder.build(
            RestateDeployer.reportDirectory(getReportPath(context), context.requiredTestClass))
    context.getStore(NAMESPACE).put(DEPLOYER_KEY, deployer)
    deployer.deployAll()
  }
}
