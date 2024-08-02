plugins {
  application
  kotlin("jvm") version "2.0.0"
  kotlin("plugin.serialization") version "2.0.0"

  alias(libs.plugins.ksp)
  id("org.jsonschema2pojo") version "1.2.1"

  id("com.diffplug.spotless") version "6.22.0"
  id("com.github.johnrengelman.shadow") version "8.1.1"
  id("com.github.jk1.dependency-license-report") version "2.8"
}

group = "dev.restate.sdktesting"

version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
  // OSSRH Snapshots repo
  maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/") }
}

dependencies {
  implementation(libs.clikt)
  implementation(libs.mordant)

  implementation(libs.restate.admin)
  implementation(libs.restate.sdk.common)

  ksp(libs.restate.sdk.api.kotlin.gen)
  implementation(libs.restate.sdk.api.kotlin)

  implementation(libs.junit.all)
  implementation(libs.junit.launcher)
  implementation(libs.junit.reporting)

  implementation(libs.testcontainers.core)
  implementation(libs.testcontainers.kafka)
  implementation(libs.testcontainers.toxiproxy)
  implementation(libs.docker)

  implementation(libs.log4j.api)
  implementation(libs.log4j.core)
  implementation(libs.log4j.slf4j)

  implementation("org.apache.kafka:kafka-clients:3.5.0")

  implementation(libs.kotlinx.serialization.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kaml)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.test)

  implementation(libs.jackson.core)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.toml)

  implementation(libs.assertj)
  implementation(libs.awaitility)
}

kotlin { jvmToolchain(17) }

val generatedJ2SPDir = layout.buildDirectory.dir("generated/j2sp")

sourceSets { main { java.srcDir(generatedJ2SPDir) } }

jsonSchema2Pojo {
  setSource(files("$projectDir/src/main/json"))
  targetPackage = "dev.restate.sdktesting.infra.runtimeconfig"
  targetDirectory = generatedJ2SPDir.get().asFile

  useLongIntegers = true
  includeSetters = true
  includeGetters = true
  generateBuilders = true
}

tasks {
  getByName("compileKotlin") { dependsOn(generateJsonSchema2Pojo) }

  test { useJUnitPlatform() }
}

spotless {
  kotlin {
    ktfmt()
    targetExclude("build/generated/**/*.kt")
    licenseHeaderFile("$rootDir/config/license-header")
  }
  kotlinGradle { ktfmt() }
  java {
    googleJavaFormat()
    targetExclude("build/generated/**/*.java")
    licenseHeaderFile("$rootDir/config/license-header")
  }
}

tasks.named("check") { dependsOn("checkLicense") }

licenseReport {
  renderers = arrayOf(com.github.jk1.license.render.CsvReportRenderer())

  excludeBoms = true

  excludes =
      arrayOf(
          "io.vertx:vertx-stack-depchain", // Vertx bom file
          "com.google.guava:guava-parent", // Guava bom
          // kotlinx dependencies are APL 2, but somehow the plugin doesn't recognize that.
          "org.jetbrains.kotlinx:kotlinx-coroutines-core",
          "org.jetbrains.kotlinx:kotlinx-serialization-core",
          "org.jetbrains.kotlinx:kotlinx-serialization-json",
      )

  allowedLicensesFile = file("$rootDir/config/allowed-licenses.json")
  filters =
      arrayOf(
          com.github.jk1.license.filter.LicenseBundleNormalizer(
              "$rootDir/config/license-normalizer-bundle.json", true))
}

application { mainClass = "dev.restate.sdktesting.MainKt" }
