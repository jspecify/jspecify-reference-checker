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
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.stream.Collectors.toList;
import static org.jspecify.conformance.AbstractConformanceTest.ExpectedFact.readExpectedFact;

import com.google.common.base.Ascii;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.io.CharSink;
import com.google.common.io.CharSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/**
 * A test that analyzes source files and compares reported facts to expected facts declared in each
 * file.
 *
 * <p>To configure:
 *
 * <ul>
 *   <li>Set the system property {@code JSpecifyConformanceTest.sourceDirectory} to the location of
 *       the JSpecify conformance test sources.
 *   <li>Set the sytem property {@code JSpecifyConformanceTest.report} to the location of the stored
 *       test report.
 * </ul>
 *
 * <p>The test can run in one of three modes, depending on the value of the {@code
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
 */
public abstract class AbstractConformanceTest {

  private final Path testDirectory;
  private final CharSource testReportSource;
  private final CharSink testReportSink;

  protected AbstractConformanceTest(Path testDirectory, Path testReport) {
    this.testDirectory = testDirectory;
    this.testReportSource = asCharSource(testReport, UTF_8);
    this.testReportSink = asCharSink(testReport, UTF_8);
  }

  protected AbstractConformanceTest() {
    this(
        systemPropertyPath(
            "JSpecifyConformanceTest.sourceDirectory",
            "the location of the JSpecify conformance test sources"),
        systemPropertyPath(
            "JSpecifyConformanceTest.report",
            "the location of the JSpecify conformance test report"));
  }

  /** Returns the directory that is the root of all test inputs. */
  protected final Path getTestDirectory() {
    return testDirectory;
  }

  private ConformanceTestReport runTests() throws IOException {
    try (Stream<Path> paths = walk(testDirectory)) {
      return paths
          .filter(path -> path.toFile().isDirectory())
          .flatMap(this::javaFileGroups)
          .flatMap(this::analyzeFiles)
          .reduce(ConformanceTestReport.EMPTY, ConformanceTestReport::combine);
    }
  }

  private Stream<ConformanceTestReport> analyzeFiles(List<Path> files) {
    ImmutableListMultimap<Path, ReportedFact> reportedFactsByFile =
        index(analyze(ImmutableList.copyOf(files)), ReportedFact::getFile);
    return files.stream()
        .map(testDirectory::relativize)
        .map(
            file ->
                ConformanceTestReport.forFile(
                    file, reportedFactsByFile.get(file), readExpectedFacts(file)));
  }

  /**
   * Analyzes a nonempty set of Java source {@code files} that may refer to each other.
   *
   * @return the facts reported by the analysis
   */
  protected abstract Iterable<ReportedFact> analyze(ImmutableList<Path> files);

  /** Reads {@link ExpectedFact}s from comments in a file. */
  private ImmutableList<ExpectedFact> readExpectedFacts(Path file) {
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

  private Stream<List<Path>> javaFileGroups(Path directory) {
    ImmutableList<Path> files = javaFilesInDirectory(directory);
    if (files.isEmpty()) {
      return Stream.empty();
    }
    if (directory.equals(testDirectory)) {
      // Each file in the top-level directory is in a group by itself.
      return partition(files, 1).stream();
    } else {
      // Group files in other directories.
      return Stream.of(files);
    }
  }

  private static ImmutableList<Path> javaFilesInDirectory(Path directory) {
    try (Stream<Path> files = Files.list(directory)) {
      return files.filter(f -> f.toString().endsWith(".java")).collect(toImmutableList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path systemPropertyPath(String key, String description) {
    return Paths.get(
        requireNonNull(
            System.getProperty(key),
            String.format("Set system property %s to %s.", key, description)));
  }

  @Test
  public void checkConformance() throws IOException {
    ConformanceTestReport testResults = runTests();
    switch (Mode.fromEnvironment()) {
      case DETAILS:
        System.out.print(testResults.report(true));
        // fall-through

      case COMPARE:
        assertThat(testResults.report(false)).isEqualTo(testReportSource.read());
        break;

      case WRITE:
        testReportSink.write(testResults.report(false));
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
      String mode = Strings.nullToEmpty(System.getenv(ENV_VARIABLE));
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
