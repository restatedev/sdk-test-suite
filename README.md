# Restate SDK test suite

This tool is a test suite/conformance suite runner for Restate SDKs.

## Architecture

This tool requires the SDK developer to implement a well-defined set of services, specified [here](./src/main/kotlin/dev/restate/sdktesting/contracts), and package the application as docker container. 
For an example implementation, look [here](https://github.com/restatedev/sdk-java/tree/main/test-services/src/main/kotlin/dev/restate/sdk/testservices).

The tool then starts for each test class Restate alongside with the service deployment container, and sends some requests to it.

The tool is split into test suite, test classes and test methods. 
A test suite is composed by multiple test classes, which itself is composed by multiple test methods. 
Test methods usually run in parallel wrt the other methods in the same class, and each individual class deploys an isolated docker network with its own runtime and its own service deployment container. 
Test suites run the same set of test classes, with different parameters of the runtime tuned.

## CI usage

Check as example: https://github.com/restatedev/sdk-python/blob/main/.github/workflows/integration.yaml

## Running locally

You need a JVM >= 21, and the test tool. You can get the test tool from the [releases page](https://github.com/restatedev/sdk-test-suite/releases). If you're investigating a CI failure, you might need the exact tool version the CI is currently using (which is usually printed in the title of the CI task).

To run all the tests:

```shell
java -jar restate-sdk-test-suite.jar run <TEST_SERVICES_IMAGE>
```

To run an individual test:

```shell
java -jar restate-sdk-test-suite.jar run --test-suite=<SUITE> --test-name=<TEST_NAME> <TEST_SERVICES_IMAGE>
```

To change the runtime container image, use `--restate-container-image=<RUNTIME_CONTAINER_IMAGE>`.

By default, the tool will always pull images, unless they have the repository prefix `restate.local` or `localhost`. To always try to use the local cache, use `--image-pull-policy=CACHED`.

All the environment variables prefixed with `RUST_` and `RESTATE_` will be propagated to every deployed runtime container. 

### Exclusions

Some SDKs might not implement all the features. For this purpose, the tool allows to configure the excluded test classes in an ad-hoc file. To run with the exclusions file:

```shell
java -jar restate-sdk-test-suite.jar run --exclusions-file exclusions.yaml <TEST_SERVICES_IMAGE>
```

After every run a new exclusions file is generated in the test report, with the failed/skipped tests.

## Local debug

You can debug the service deployment by exposing it to a local port, and ask the tool to use a local port instead of deploying a container. To do so:

* Run the service with your IDE and the debugger
* Run `java -jar restate-sdk-test-suite.jar debug --test-suite=<TEST_SUITE> --test-name=<TEST_NAME> default-service=<PORT>`

When the test starts, it will also print to console the environment variables the service deployment should be started with.

Using the `debug` command, you can also:

* Expose the environment to your localhost (e.g. to use the CLI to introspect the state) and keep it up and running after the end of the test using:

```shell
java -jar restate-sdk-test-suite.jar debug \
  --local-ingress-port=8080 --local-admin-port=9070 --retain-after-end \
  --test-suite=<TEST_SUITE> --test-name=<TEST_NAME> \
  default-service=<PORT>
```

Please note, some tests requiring to kill/stop the service deployment won't work with the `debug` command.

## Building this tool

```shell
./gradlew shadowJar
```

## Releasing this tool

Just push a new git tag:

```shell
git tag v1.1 
git push --tags
```