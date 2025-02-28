// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

const val RESTATE_RUNTIME = "runtime"
const val RUNTIME_INGRESS_ENDPOINT_PORT = 8080
const val RUNTIME_NODE_PORT = 5122
internal const val RUNTIME_ADMIN_ENDPOINT_PORT = 9070

@Target(AnnotationTarget.VALUE_PARAMETER) annotation class InjectClient

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class InjectContainerHandle(val hostName: String)

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class InjectContainerPort(val hostName: String, val port: Int)

@Target(AnnotationTarget.VALUE_PARAMETER) annotation class InjectIngressURI

@Target(AnnotationTarget.VALUE_PARAMETER) annotation class InjectAdminURI
