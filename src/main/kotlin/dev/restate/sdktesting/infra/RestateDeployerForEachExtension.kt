// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate e2e tests,
// which are released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/e2e/blob/main/LICENSE

package dev.restate.sdktesting.infra

import org.junit.jupiter.api.extension.*

@Deprecated("Split tests in different classes in these cases")
class RestateDeployerForEachExtension(
    private val deployerFactory: RestateDeployer.Builder.() -> Unit
) : BeforeEachCallback, BaseRestateDeployerExtension() {

  override fun beforeEach(context: ExtensionContext) {
    val builder = RestateDeployer.builder()
    deployerFactory.invoke(builder)
    val deployer = builder.build()
    deployer.deployAll(
        RestateDeployer.reportDirectory(
            getReportPath(context), context.requiredTestClass, context.requiredTestMethod))
    context.getStore(NAMESPACE).put(DEPLOYER_KEY, deployer)
  }
}
