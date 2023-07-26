package tests.conformance;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Lists.partition;
import static com.google.common.collect.Multimaps.toMultimap;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllLines;
import static java.nio.file.Files.walk;
import static java.util.Arrays.stream;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.ExpectedFact.readExpectedFact;
import static tests.conformance.ConformanceTestSubject.assertThat;

import com.google.common.base.Ascii;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Streams;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.ExpectedFact;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.ExpectedFactAssertion;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.NoUnexpectedFactsAssertion;
import tests.conformance.ConformanceTestReport.ConformanceTestResult;

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
  private final Path testReport;

  protected AbstractConformanceTest(Path testDirectory, Path testReport) {
    this.testDirectory = testDirectory;
    this.testReport = testReport;
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
          .collect(collectingAndThen(toImmutableSet(), ConformanceTestReport::new));
    }
  }

  private Stream<ConformanceTestResult> analyzeFiles(List<Path> files) {
    ImmutableMap<Path, ListMultimap<Long, ReportedFact>> reportedFactsByFile =
        Streams.stream(analyze(ImmutableList.copyOf(files)))
            .collect(ReportedFact.BY_FILE_AND_LINE_NUMBER);
    return files.stream().flatMap(file -> testResultsForFile(file, reportedFactsByFile).stream());
  }

  /**
   * Analyzes a nonempty set of Java source {@code files} that may refer to each other.
   *
   * @return the facts reported by the analysis
   */
  protected abstract Iterable<ReportedFact> analyze(ImmutableList<Path> files);

  private ImmutableSet<ConformanceTestResult> testResultsForFile(
      Path file, ImmutableMap<Path, ListMultimap<Long, ReportedFact>> reportedFactsByFile) {
    Path relativeFile = testDirectory.relativize(file);
    ListMultimap<Long, ReportedFact> reportedFactsInFile =
        requireNonNullElse(reportedFactsByFile.get(relativeFile), ArrayListMultimap.create());
    ImmutableSet.Builder<ConformanceTestResult> report = ImmutableSet.builder();
    readExpectedFacts(file).stream()
        .collect(groupingBy(ExpectedFactAssertion::getLineNumber))
        .forEach(
            (lineNumber, expectedFactAssertions) -> {
              List<ReportedFact> reportedFactsOnLine = reportedFactsInFile.get(lineNumber);
              for (ExpectedFactAssertion expectedFactAssertion : expectedFactAssertions) {
                ExpectedFact expectedFact = expectedFactAssertion.getFact();
                report.add(
                    new ConformanceTestResult(
                        expectedFactAssertion,
                        // Pass if any reported fact matches this expected fact.
                        reportedFactsOnLine.stream()
                            .anyMatch(reportedFact -> reportedFact.matches(expectedFact))));
              }
              // Remove all reported facts that match any expected fact.
              reportedFactsOnLine.removeIf(
                  reportedFact ->
                      expectedFactAssertions.stream()
                          .map(ExpectedFactAssertion::getFact)
                          .anyMatch(reportedFact::matches));
            });

    // By now, only reported facts that don't match any expected fact remain in reportedFactsInFile.
    report.add(
        new ConformanceTestResult(
            new NoUnexpectedFactsAssertion(relativeFile),
            reportedFactsInFile.values().stream()
                .filter(ReportedFact::mustBeExpected)
                .collect(toImmutableList())));
    return report.build();
  }

  /** Reads {@link ExpectedFactAssertion}s from comments in a file. */
  private ImmutableList<ExpectedFactAssertion> readExpectedFacts(Path file) {
    Path relativeFile = testDirectory.relativize(file);
    try {
      ImmutableList.Builder<ExpectedFactAssertion> expectedFactAssertions = ImmutableList.builder();
      List<ExpectedFact> expectedFacts = new ArrayList<>();
      for (ListIterator<String> i = readAllLines(file, UTF_8).listIterator(); i.hasNext(); ) {
        String line = i.next();
        Matcher matcher = EXPECTATION_COMMENT.matcher(line);
        ExpectedFact fact =
            matcher.matches() ? readExpectedFact(matcher.group("expectation")) : null;
        if (fact != null) {
          expectedFacts.add(fact);
        } else {
          long lineNumber = i.nextIndex();
          expectedFacts.stream()
              .map(
                  expectedFact -> new ExpectedFactAssertion(relativeFile, lineNumber, expectedFact))
              .forEach(expectedFactAssertions::add);
          expectedFacts.clear();
        }
      }
      return expectedFactAssertions.build();
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
      case COMPARE:
        assertThat(testResults).matches(ConformanceTestReport.readFile(testReport));
        break;

      case WRITE:
        testResults.writeFile(testReport);
        break;

      case DETAILS:
        assertThat(testResults).allTestsPass();
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

  /**
   * An assertion about a test source file. There are two kinds of assertions: {@link
   * ExpectedFactAssertion}s and {@link NoUnexpectedFactsAssertion}s.
   */
  public abstract static class ConformanceTestAssertion
      implements Comparable<ConformanceTestAssertion> {

    private final Path file;

    protected ConformanceTestAssertion(Path file) {
      this.file = file;
    }

    /** The file path relative to the test source root. */
    public final Path getFile() {
      return file;
    }

    @Override
    public final int compareTo(ConformanceTestAssertion that) {
      return COMPARATOR.compare(this, that);
    }

    private static final Comparator<ConformanceTestAssertion> COMPARATOR =
        comparing(ConformanceTestAssertion::getFile)
            .thenComparing(
                cta ->
                    cta instanceof ExpectedFactAssertion
                        ? ((ExpectedFactAssertion) cta).lineNumber
                        : Long.MAX_VALUE)
            .thenComparing(ConformanceTestReport::toReportText);

    /**
     * An assertion that the tool behaves in a way consistent with a specific fact. Some of these
     * facts indicate that according to the JSpecify specification, the code in question may have an
     * error that should be reported to users; other expected facts are informational, such as the
     * expected nullness-augmented type of an expression.
     */
    public static final class ExpectedFact {
      private static final Pattern NULLNESS_MISMATCH =
          Pattern.compile("jspecify_nullness_mismatch\\b.*");

      private static final ImmutableList<Pattern> ASSERTION_PATTERNS =
          ImmutableList.of(
              NULLNESS_MISMATCH,
              // TODO: wildcard types have whitespace
              Pattern.compile("test:cannot-convert:\\S+ to \\S+"),
              Pattern.compile("test:expression-type:[^:]+:.*"),
              Pattern.compile("test:irrelevant-annotation:\\S+"));

      /**
       * Returns an expected fact representing that the source type cannot be converted to the sink
       * type in any world.
       */
      public static ExpectedFact cannotConvert(String sourceType, String sinkType) {
        return new ExpectedFact(
            String.format("test:cannot-convert:%s to %s", sourceType, sinkType));
      }

      /** Returns an expected fact representing an expected expression type. */
      public static ExpectedFact expressionType(String expressionType, String expression) {
        return new ExpectedFact(
            String.format("test:expression-type:%s:%s", expressionType, expression));
      }

      /** Returns an expected fact representing that an annotation is not relevant. */
      public static ExpectedFact irrelevantAnnotation(String annotationType) {
        return new ExpectedFact(String.format("test:irrelevant-annotation:%s", annotationType));
      }

      /** Read an {@link ExpectedFact} from a line of either a source file or a report. */
      static @Nullable ExpectedFact readExpectedFact(String text) {
        return ASSERTION_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(text).matches())
            ? new ExpectedFact(text)
            : null;
      }

      private ExpectedFact(String commentText) {
        this.commentText = commentText;
      }

      private final String commentText;

      /** The comment text representing this expected fact. */
      public String commentText() {
        return commentText;
      }

      /** Returns {@code true} if this is a legacy {@code jspecify_nullness_mismatch} assertion. */
      public boolean isNullnessMismatch() {
        return NULLNESS_MISMATCH.matcher(commentText).matches();
      }

      @Override
      public boolean equals(Object obj) {
        if (obj == this) {
          return true;
        }
        if (!(obj instanceof ExpectedFact)) {
          return false;
        }
        ExpectedFact that = (ExpectedFact) obj;
        return this.commentText.equals(that.commentText);
      }

      @Override
      public int hashCode() {
        return commentText.hashCode();
      }

      @Override
      public String toString() {
        return commentText;
      }
    }

    /**
     * An assertion that the tool behaves in a way consistent with a specific fact about a line in
     * the source code. Some of these facts indicate that according to the JSpecify specification,
     * the code in question may have an error that should be reported to users; other expected facts
     * are informational, such as the expected nullness-augmented type of an expression.
     */
    public static final class ExpectedFactAssertion extends ConformanceTestAssertion {
      private final long lineNumber;
      private final ExpectedFact fact;

      ExpectedFactAssertion(Path file, long lineNumber, ExpectedFact fact) {
        super(file);
        this.lineNumber = lineNumber;
        this.fact = fact;
      }

      /** Returns the line number of the code in the source file to which this assertion applies. */
      public long getLineNumber() {
        return lineNumber;
      }

      /** Returns the fact expected at the file and line number. */
      public ExpectedFact getFact() {
        return fact;
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof ExpectedFactAssertion)) {
          return false;
        }
        ExpectedFactAssertion that = (ExpectedFactAssertion) o;
        return this.getFile().equals(that.getFile())
            && this.lineNumber == that.lineNumber
            && this.fact.equals(that.fact);
      }

      @Override
      public int hashCode() {
        return Objects.hash(getFile(), lineNumber, fact);
      }
    }

    /**
     * An assertion that the tool does not behave in a way such that it might report an error to a
     * user for any part of the code that doesn't have a corresponding {@link
     * ExpectedFactAssertion}.
     */
    public static final class NoUnexpectedFactsAssertion extends ConformanceTestAssertion {

      NoUnexpectedFactsAssertion(Path file) {
        super(file);
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || this.getClass() != o.getClass()) {
          return false;
        }

        NoUnexpectedFactsAssertion that = (NoUnexpectedFactsAssertion) o;
        return this.getFile().equals(that.getFile());
      }

      @Override
      public int hashCode() {
        return getFile().hashCode();
      }
    }
  }

  /** A fact reported by the analysis under test. */
  public abstract static class ReportedFact {

    static final Collector<ReportedFact, ?, ImmutableMap<Path, ListMultimap<Long, ReportedFact>>>
        BY_FILE_AND_LINE_NUMBER =
            collectingAndThen(
                groupingBy(
                    ReportedFact::getFile,
                    toMultimap(ReportedFact::getLineNumber, rf -> rf, ArrayListMultimap::create)),
                ImmutableMap::copyOf);

    private final Path file;

    private final long lineNumber;

    protected ReportedFact(Path file, long lineNumber) {
      this.file = file;
      this.lineNumber = lineNumber;
    }

    public final Path getFile() {
      return file;
    }

    public final long getLineNumber() {
      return lineNumber;
    }

    /** Returns true if this reported fact must match an {@link ExpectedFactAssertion}. */
    protected abstract boolean mustBeExpected();

    /** Returns true if this reported fact matches the given expected fact. */
    protected boolean matches(ExpectedFact expectedFact) {
      return expectedFact.equals(expectedFact());
    }

    /** Returns the equivalent expected fact. */
    protected abstract @Nullable ExpectedFact expectedFact();

    /** Returns the message reported, without the file name or line number. */
    @Override
    public abstract String toString();

    public static final Comparator<ReportedFact> COMPARATOR =
        comparing(ReportedFact::getFile).thenComparing(ReportedFact::getLineNumber);
  }
}
