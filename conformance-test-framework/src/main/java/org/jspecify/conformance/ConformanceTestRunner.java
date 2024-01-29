// Copyright 2023 The JSpecify Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.jspecify.conformance;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Lists.partition;
import static com.google.common.collect.Multimaps.index;
import static com.google.common.io.MoreFiles.asCharSink;
import static com.google.common.io.MoreFiles.asCharSource;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllLines;
import static java.nio.file.Files.walk;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNullElse;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** An object that runs JSpecify conformance tests. */
public final class ConformanceTestRunner {

  /** A delegate object used by {@link ConformanceTestRunner} to analyze test input files. */
  public interface Analyzer {
    /**
     * Analyzes a nonempty set of Java source {@code files} that may refer to each other, along with
     * a classpath containing symbols the files may depend on.
     *
     * @param testDirectory the directory containing the test input files to analyze
     * @param files the source files to analyze
     * @param testDeps paths to JAR files that must be on the classpath when analyzing
     * @return the facts reported by the analysis
     */
    Iterable<ReportedFact> analyze(
        Path testDirectory, ImmutableList<Path> files, ImmutableList<Path> testDeps);
  }

  private final Analyzer analyzer;

  public ConformanceTestRunner(Analyzer analyzer) {
    this.analyzer = analyzer;
  }

  /**
   * Analyzes source files and compares reported facts to expected facts declared in each file.
   *
   * @param testDirectory the directory containing the test input files to analyze
   * @param testDeps a list of paths to JAR files that must be on the classpath when analyzing
   * @return a report of the results
   */
  public ConformanceTestReport runTests(Path testDirectory, ImmutableList<Path> testDeps)
      throws IOException {
    try (Stream<Path> paths = walk(testDirectory)) {
      return paths
          .filter(path -> path.toFile().isDirectory())
          .flatMap(
              directory -> {
                Stream<ImmutableList<Path>> groups = javaFileGroups(directory);
                return directory.equals(testDirectory)
                    ? groups.flatMap(files -> partition(files, 1).stream())
                    : groups;
              })
          .flatMap(files -> analyzeFiles(files, testDeps, testDirectory))
          .reduce(ConformanceTestReport.EMPTY, ConformanceTestReport::combine);
    }
  }

  private Stream<ConformanceTestReport> analyzeFiles(
      List<Path> files, ImmutableList<Path> testDeps, Path testDirectory) {
    ImmutableListMultimap<Path, ReportedFact> reportedFactsByFile =
        index(
            analyzer.analyze(testDirectory, ImmutableList.copyOf(files), testDeps),
            ReportedFact::getFile);
    return files.stream()
        .map(testDirectory::relativize)
        .map(
            file ->
                ConformanceTestReport.forFile(
                    file, reportedFactsByFile.get(file), readExpectedFacts(file, testDirectory)));
  }

  /** Reads {@link ExpectedFact}s from comments in a file. */
  private static ImmutableList<ExpectedFact> readExpectedFacts(Path file, Path testDirectory) {
    try {
      ImmutableList.Builder<ExpectedFact> expectedFacts = ImmutableList.builder();
      Map<Long, String> facts = new HashMap<>();
      for (ListIterator<String> i = readAllLines(testDirectory.resolve(file), UTF_8).listIterator();
          i.hasNext(); ) {
        String line = i.next();
        long lineNumber = i.nextIndex();
        Matcher matcher = EXPECTATION_COMMENT.matcher(line);
        String fact = matcher.matches() ? readExpectedFact(matcher.group("expectation")) : null;
        if (fact != null) {
          facts.put(lineNumber, fact);
        } else {
          facts.forEach(
              (factLineNumber, expectedFact) ->
                  expectedFacts.add(
                      new ExpectedFact(file, lineNumber, expectedFact, factLineNumber)));
          facts.clear();
        }
      }
      return expectedFacts.build();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final Pattern EXPECTATION_COMMENT = Pattern.compile("\\s*// (?<expectation>.*)");

  private static Stream<ImmutableList<Path>> javaFileGroups(Path directory) {
    ImmutableList<Path> files = javaFilesInDirectory(directory);
    return files.isEmpty() ? Stream.empty() : Stream.of(files);
  }

  private static ImmutableList<Path> javaFilesInDirectory(Path directory) {
    try (Stream<Path> files = Files.list(directory)) {
      return files.filter(f -> f.toString().endsWith(".java")).collect(toImmutableList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Analyzes source files and compares reported facts to expected facts declared in each file.
   *
   * <p>The analysis runs in one of three modes, depending on the value of the {@code
   * JSPECIFY_CONFORMANCE_TEST_MODE} environment variable:
   *
   * <dl>
   *   <dt>{@code compare} or empty
   *   <dd>Compare the analysis to the stored report, and fail if anything has changed. Note that
   *       failure isn't always bad! If an assertion used to fail and now passes, this test will
   *       "fail".
   *   <dt>{@code write}
   *   <dd>Write the analysis to the report file. Always passes.
   *   <dt>{@code details}
   *   <dd>Fail if any assertion fails. Report details of unexpected facts.
   * </dl>
   *
   * @param testDirectory the directory containing the test input files to analyze
   * @param testDeps paths to JAR files that must be on the classpath when analyzing
   * @param testReport the file to read or write
   */
  public void checkConformance(Path testDirectory, ImmutableList<Path> testDeps, Path testReport)
      throws IOException {
    ConformanceTestReport testResults = runTests(testDirectory, testDeps);
    switch (Mode.fromEnvironment()) {
      case DETAILS:
        System.out.print(testResults.report(true));
        // fall-through

      case COMPARE:
        assertThat(testResults.report(false)).isEqualTo(asCharSource(testReport, UTF_8).read());
        break;

      case WRITE:
        asCharSink(testReport, UTF_8).write(testResults.report(false));
        break;

      default:
        throw new AssertionError(Mode.fromEnvironment());
    }
  }

  private enum Mode {
    COMPARE,
    WRITE,
    DETAILS,
    ;

    private static final String ENV_VARIABLE = "JSPECIFY_CONFORMANCE_TEST_MODE";

    static Mode fromEnvironment() {
      String mode = nullToEmpty(System.getenv(ENV_VARIABLE));
      try {
        return mode.isEmpty() ? COMPARE : valueOf(Ascii.toUpperCase(mode));
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException(
            String.format(
                "Environment variable %s must be one of %s if set, but it was \"%s\".",
                ENV_VARIABLE,
                stream(values()).map(Object::toString).map(Ascii::toLowerCase).collect(toList()),
                mode));
      }
    }
  }
}
