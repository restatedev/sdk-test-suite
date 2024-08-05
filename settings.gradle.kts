plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0" }

rootProject.name = "restate-sdk-test-suite"

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    // OSSRH Snapshots repo
    maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/") }
  }

  versionCatalogs {
    create("libs") {
      library("restate-sdk-common", "dev.restate", "sdk-common").versionRef("restate")
      library("restate-admin", "dev.restate", "admin-client").versionRef("restate")
      library("restate-sdk-api-kotlin", "dev.restate", "sdk-api-kotlin").versionRef("restate")
      library("restate-sdk-api-kotlin-gen", "dev.restate", "sdk-api-kotlin-gen")
          .versionRef("restate")

      version("log4j", "2.19.0")
      library("log4j-api", "org.apache.logging.log4j", "log4j-api").versionRef("log4j")
      library("log4j-core", "org.apache.logging.log4j", "log4j-core").versionRef("log4j")
      library("log4j-slf4j", "org.apache.logging.log4j", "log4j-slf4j-impl").versionRef("log4j")

      version("jackson", "2.16.1")
      library("jackson-core", "com.fasterxml.jackson.core", "jackson-core").versionRef("jackson")
      library("jackson-databind", "com.fasterxml.jackson.core", "jackson-databind")
          .versionRef("jackson")
      library(
              "jackson-parameter-names",
              "com.fasterxml.jackson.module",
              "jackson-module-parameter-names")
          .versionRef("jackson")
      library("jackson-java8", "com.fasterxml.jackson.datatype", "jackson-datatype-jdk8")
          .versionRef("jackson")
      library("jackson-datetime", "com.fasterxml.jackson.datatype", "jackson-datatype-jsr310")
          .versionRef("jackson")
      library("jackson-kotlin", "com.fasterxml.jackson.module", "jackson-module-kotlin")
          .versionRef("jackson")
      library("jackson-toml", "com.fasterxml.jackson.dataformat", "jackson-dataformat-toml")
          .versionRef("jackson")

      version("junit-jupiter", "5.10.0")
      version("junit-platform", "1.10.3")
      library("junit-all", "org.junit.jupiter", "junit-jupiter").versionRef("junit-jupiter")
      library("junit-launcher", "org.junit.platform", "junit-platform-launcher")
          .versionRef("junit-platform")
      library("junit-reporting", "org.junit.platform", "junit-platform-reporting")
          .versionRef("junit-platform")

      version("assertj", "3.24.2")
      library("assertj", "org.assertj", "assertj-core").versionRef("assertj")

      version("testcontainers", "1.20.1")
      library("testcontainers-core", "org.testcontainers", "testcontainers")
          .versionRef("testcontainers")
      library("testcontainers-kafka", "org.testcontainers", "kafka").versionRef("testcontainers")
      library("testcontainers-toxiproxy", "org.testcontainers", "toxiproxy")
          .versionRef("testcontainers")
      // Keep this in sync with the version used by testcontainers
      library("docker", "com.github.docker-java:docker-java:3.3.6")

      version("awaitility", "4.2.1")
      library("awaitility", "org.awaitility", "awaitility-kotlin").versionRef("awaitility")

      library("kotlinx-serialization-core", "org.jetbrains.kotlinx", "kotlinx-serialization-core")
          .version("1.6.2")
      library("kotlinx-serialization-json", "org.jetbrains.kotlinx", "kotlinx-serialization-json")
          .version("1.6.2")
      library("kaml", "com.charleskorn.kaml:kaml:0.60.0")
      library("kotlinx-coroutines-core", "org.jetbrains.kotlinx", "kotlinx-coroutines-core")
          .version("1.8.1")
      library("kotlinx-coroutines-test", "org.jetbrains.kotlinx", "kotlinx-coroutines-test")
          .version("1.8.1")

      version("ksp", "2.0.0-1.0.23")
      library("symbol-processing-api", "com.google.devtools.ksp", "symbol-processing-api")
          .versionRef("ksp")
      plugin("ksp", "com.google.devtools.ksp").versionRef("ksp")
    }
  }
}
