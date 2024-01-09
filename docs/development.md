# Development

## Codevelopment with Checker Framework fork

This project depends on
an [unreleased fork of the Checker Framework][jspecify-checker-framework].
(The [main-eisop branch] represents ongoing work to depend on a released version
of the [EISOP] fork instead.)

Because of that dependency, this build clones that unreleased fork into the
sibling directory `../checker-framwork`.
_That_ build then clones some other projects into other sibling directories. It
expects `../jdk` to contain an annotated JDK, so our build
clones [JSpecify's][jspecify-jdk] there.

## Codevelopment with JSpecify

This project depends on two artifacts built from the main [JSpecify repo]
[jspecify-jspecify]:

1. the annotations: `org.jspecify:jspecify`
2. the conformance test suite: `org.jspecify.conformance:conformance-tests`

For each of those dependencies, developers can depend on either a published
version (fixed or snapshot) or a local build.

In order to depend on a specific commit or uncommitted state of those artifacts,
clone the repo (or your fork) somewhere, and pass
`--include-build path/to/jspecify` to Gradle when building. The local clone will
be used for both the annotations and the conformance test suite.

By default the reference checker depends on version `0.3.0` of the annotations,
and version `0.0.0-SNAPSHOT` of the conformance test suite.

In order to depend on a different published version of either artifact, set
Gradle properties on the command line.

* `-Porg.jspecify:jspecify=0.2.0` would change the version of the annotations.
* `-Porg.jspecify.conformance:conformance-tests=0.3.0` would change the version
  of the conformance test suite.

[EISOP]: https://github.com/eisop/checker-framework
[jspecify-checker-framework]: https://github.com/jspecify/checker-framework
[jspecify-jdk]: https://github.com/jspecify/jdk
[jspecify-jspecify]: https://github.com/jspecify/jspecify
[main-eisop branch]: https://github.com/jspecify/jspecify-reference-checker/tree/main-eisop
