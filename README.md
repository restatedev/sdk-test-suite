# Restate SDK test suite

TODO, more coming soon

## CI usage

-> download the test-suite jar already built
-> prepare the service docker image with the services required by the tests
-> run `java -jar restate-sdk-test-suite.jar run restatedev/e2e-java-services`
-> package the reports to publish on the github ui

## Local debugging usage

-> Run the service with your IDE and the debugger
-> Run `java -jar restate-sdk-test-suite.jar debug --test-config=<TEST_CONFIG> --test-name=<TEST_NAME> default-service=9080`