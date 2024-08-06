// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.infra

import org.testcontainers.images.AbstractImagePullPolicy
import org.testcontainers.images.ImageData
import org.testcontainers.utility.DockerImageName

object LocalAlwaysPullPolicy : AbstractImagePullPolicy() {
  override fun shouldPullCached(imageName: DockerImageName, localImageData: ImageData): Boolean {
    return !(imageName.registry.equals("restate.local", ignoreCase = true) ||
        imageName.registry.equals("localhost", ignoreCase = true))
  }
}
