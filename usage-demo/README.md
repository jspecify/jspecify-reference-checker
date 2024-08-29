# JSpecify Reference Checker Usage Demo

This is a simple demonstration for how a gradle project can use the JSpecify Reference Checker.

Until the JSpecify Reference Checker is released to Maven Central, in the parent directory,
one must first run:

````
./gradlew PublishToMavenLocal
````

to publish the JSpecify Reference Checker to the local Maven repository.

Then, in the current `usage-demo` directory, one can run:

````
../gradlew assemble
````

to assemble the demo project and get a set of three expected error messages
(plus one warning from Error Prone).
