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
import static org.jspecify.conformance.ConformanceTestRunner.ExpectedFact.readExpectedFact;

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
  public interface Analyzer {
    /**
     * Analyzes a nonempty set of Java source {@code files} that may refer to each other, along with
     * a classpath containing symbols the files may depend on.
     *
     * @return the facts reported by the analysis
     */
    Iterable<ReportedFact> analyze(
        ImmutableList<Path> files, ImmutableList<Path> testDeps, Path testDirectory);
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
            analyzer.analyze(ImmutableList.copyOf(files), testDeps, testDirectory),
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
   * @param testDeps a list of paths to JAR files that must be on the classpath when analyzing
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

  /** An expected or reported fact within a test input file. */
  public abstract static class Fact {

    private final Path file;
    private final long lineNumber;

    protected Fact(Path file, long lineNumber) {
      this.file = file;
      this.lineNumber = lineNumber;
    }

    /** The file path relative to the test source root. */
    public final Path getFile() {
      return file;
    }

    /** Returns the line number of the code in the source file to which this fact applies. */
    public final long getLineNumber() {
      return lineNumber;
    }

    /** The fact text. */
    public abstract String getFactText();
  }

  /**
   * An assertion that the tool behaves in a way consistent with a specific fact about a line in the
   * source code. Some of these facts indicate that according to the JSpecify specification, the
   * code in question may have an error that should be reported to users; other expected facts are
   * informational, such as the expected nullness-augmented type of an expression.
   */
  public static final class ExpectedFact extends Fact {
    private static final Pattern NULLNESS_MISMATCH =
        Pattern.compile("jspecify_nullness_mismatch\\b.*");

    private static final ImmutableList<Pattern> ASSERTION_PATTERNS =
        ImmutableList.of(
            NULLNESS_MISMATCH,
            // TODO: wildcard types have whitespace
            Pattern.compile("test:cannot-convert:\\S+ to \\S+"),
            Pattern.compile("test:expression-type:[^:]+:.*"),
            Pattern.compile("test:irrelevant-annotation:\\S+"),
            Pattern.compile("test:sink-type:[^:]+:.*"));

    /**
     * Returns an expected fact representing that the source type cannot be converted to the sink
     * type in any world.
     */
    public static String cannotConvert(String sourceType, String sinkType) {
      return String.format("test:cannot-convert:%s to %s", sourceType, sinkType);
    }

    /** Returns an expected fact representing an expected expression type. */
    public static String expressionType(String expressionType, String expression) {
      return String.format("test:expression-type:%s:%s", expressionType, expression);
    }

    /** Returns an expected fact representing that an annotation is not relevant. */
    public static String irrelevantAnnotation(String annotationType) {
      return String.format("test:irrelevant-annotation:%s", annotationType);
    }

    /** Returns an expected fact representing that an annotation is not relevant. */
    public static String sinkType(String sinkType, String sink) {
      return String.format("test:sink-type:%s:%s", sinkType, sink);
    }

    /**
     * Returns {@code true} if {@code fact} is a legacy {@code jspecify_nullness_mismatch}
     * assertion.
     */
    public static boolean isNullnessMismatch(String fact) {
      return NULLNESS_MISMATCH.matcher(fact).matches();
    }

    /** Read an expected fact from a line of either a source file or a report. */
    static @Nullable String readExpectedFact(String text) {
      return ASSERTION_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(text).matches())
          ? text
          : null;
    }

    private final String fact;
    private final long factLineNumber;

    ExpectedFact(Path file, long lineNumber, String fact, long factLineNumber) {
      super(file, lineNumber);
      this.fact = fact;
      this.factLineNumber = factLineNumber;
    }

    @Override
    public String getFactText() {
      return fact;
    }

    /** Returns the line number in the input file where the expected fact is. */
    public long getFactLineNumber() {
      return factLineNumber;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ExpectedFact)) {
        return false;
      }
      ExpectedFact that = (ExpectedFact) o;
      return this.getFile().equals(that.getFile())
          && this.getLineNumber() == that.getLineNumber()
          && this.fact.equals(that.fact);
    }

    @Override
    public int hashCode() {
      return Objects.hash(getFile(), getLineNumber(), fact);
    }
  }

  /** A fact reported by the analysis under test. */
  public abstract static class ReportedFact extends Fact {
    protected ReportedFact(Path file, long lineNumber) {
      super(file, lineNumber);
    }

    @Override
    public String getFactText() {
      return requireNonNullElse(expectedFact(), toString());
    }

    /** Returns true if this reported fact must match an {@link ExpectedFact}. */
    protected abstract boolean mustBeExpected();

    /** Returns true if this reported fact matches the given expected fact. */
    protected boolean matches(String expectedFact) {
      return expectedFact.equals(expectedFact());
    }

    /** Returns the equivalent expected fact. */
    protected abstract @Nullable String expectedFact();

    /** Returns the message reported, without the file name or line number. */
    @Override
    public abstract String toString();
  }
}
