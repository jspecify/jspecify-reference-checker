# JSpecify Reference Checker Usage Demo

This is a simple demonstration for how a gradle project can use the JSpecify Reference Checker.

Until the JSpecify Reference Checker is released to Maven Central, on must first run:

````
./gradlew PublishToMavenLocal
````

Then, in the current directory, one can run:

````
../gradlew assemble
````

To assemble the demo project and get a set of three expected error messages (plus one warning from Error Prone).
