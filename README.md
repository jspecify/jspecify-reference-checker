# The JSpecify Reference Checker

This project is **under development** to become the **reference implementation** for the [JSpecify](http://jspecify.org) nullness specification (and later, its other specifications as well). The current goal we're working toward is conformance with version `0.3` of that spec, but it's not there yet.

## A "reference" checker

Though it does *check your references* (to see if they're null), that's not the intended meaning!

This tool's purpose is to report nullness-related findings **correctly** according to the JSpecify standard for nullness annotation semantics. 

But that's its *only* job. Notably, it is:

* **not** expected to have good **performance** characteristics
* **not** expected to have good **usability** characteristics

... and there is no intention to make it competitive in these ways. Of course real tools by our partner organizations will be better.

## Relationship to Checker Framework and EISOP

The [EISOP project](https://eisop.github.io/) maintains a fork of [Checker Framework](https://checkerframework.org/), and JSpecify conformance is one of its primary goals.

This tool happens to be built on top of another fork of these ([here](https://github.com/jspecify/checker-framework)). However, please view this relationship as **implementation detail** only. Building a reference checker from scratch would simply have been too difficult, so we needed to base it on some existing tool. The choice of which tool was made purely for expediency and is **subject to change**.

## Usage

Building and running this tool requires building code from several other repositories, but these instructions will take care of that automatically.

These instructions might require workarounds or fail outright. Please file an issue if you have any trouble!

### Prework

Ideally set `JAVA_HOME` to a JDK 11 or JDK 17 installation.

Make sure you have Apache Maven installed and in your PATH, or the Gradle build will fail:

```sh
mvn
```

Choose a name for a brand new directory that doesn't already exist. It may be convenient to set `$root_dir` to that absolute path:

```sh
root_dir=/absolute/pathname/for/fresh/new/directory
```

... because then you can paste the commands below exactly as they are.

### Building

Now get the code for this project. 

**Warning:** If you clone this project under a different name, watch out, as the later steps assume it to be named exactly `jspecify-reference-checker`.

```sh
mkdir $root_dir
cd $root_dir
git clone https://github.com/jspecify/jspecify-reference-checker
cd jspecify-reference-checker
```

Now build it, which will retrieve a lot of other code too, and will take 10-15
minutes:

```sh
./gradlew assemble
```

### Demonstration

Run the checker on the sample file:

```sh
cd $root_dir/jspecify-reference-checker
./demo SimpleSample.java
```

(If you haven't [built the reference checker](#building) yet, this will build it
the first time you run it.)

After that, you should see:

```
SimpleSample.java:7: error: [nullness] incompatible types in return.
    return mightBeNull;
           ^
  type of expression: Object?
  method return type: Object
1 error
```

Note that the `demo` script is not complicated, and illustrates how you can enable checking for your own code as well.

### Testing

To perform a minimal test, run:

```sh
cd $root_dir/jspecify-reference-checker
./gradlew test
```

To run the (incomplete) conformance test suite, check out the `main` branch of `jspecify` and run `conformanceTest`:

```sh
git -C $root_dir/jspecify checkout main
cd $root_dir/jspecify-reference-checker
./gradlew conformanceTests
```

To run the (legacy) "samples" test suite, check out the `samples-google-prototype` branch of `jspecify` and run `jspecifySamplesTest`:

```sh
git -C $root_dir/jspecify checkout samples-google-prototype
cd $root_dir/jspecify-reference-checker
./gradlew jspecifySamplesTest
```

