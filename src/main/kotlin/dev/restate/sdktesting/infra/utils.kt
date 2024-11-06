// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

import org.testcontainers.images.ImagePullPolicy
import org.testcontainers.images.PullPolicy

internal fun dev.restate.sdktesting.infra.PullPolicy.toTestContainersImagePullPolicy():
    ImagePullPolicy {
  return when (this) {
    dev.restate.sdktesting.infra.PullPolicy.ALWAYS -> LocalAlwaysPullPolicy
    dev.restate.sdktesting.infra.PullPolicy.CACHED -> PullPolicy.defaultPolicy()
  }
}
