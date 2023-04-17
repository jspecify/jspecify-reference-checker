package tests.conformance;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
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
import static tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.ExpectedFactAssertion.readExpectedFact;
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
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.ExpectedFactAssertion;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.ExpectedFactAssertion.CannotConvert;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.ExpectedFactAssertion.NullnessMismatch;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.NoUnexpectedFactsAssertion;

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
          .filter(p -> p.toFile().isDirectory())
          .map(AbstractConformanceTest::javaFilesInDirectory)
          .filter(files -> !files.isEmpty())
          .flatMap(this::analyzeFiles)
          .collect(collectingAndThen(toImmutableSet(), ConformanceTestReport::new));
    }
  }

  private Stream<ConformanceTestResult> analyzeFiles(ImmutableList<Path> files) {
    ImmutableMap<Path, ListMultimap<Long, ReportedFact>> reportedFactsByFile =
        Streams.stream(analyze(files)).collect(ReportedFact.BY_FILE_AND_LINE_NUMBER);
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
            (lineNumber, expectedFacts) -> {
              List<ReportedFact> reportedFactsOnLine = reportedFactsInFile.get(lineNumber);
              for (ExpectedFactAssertion expectedFact : expectedFacts) {
                report.add(
                    new ConformanceTestResult(
                        expectedFact,
                        // Removes matching reported facts and returns true (pass) if any matched.
                        reportedFactsOnLine.removeIf(expectedFact::isSatisfied)));
              }
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
    List<ExpectedFactAssertion.Factory> expectations = new ArrayList<>();
    try {
      ImmutableList.Builder<ExpectedFactAssertion> expectedFacts = ImmutableList.builder();
      for (ListIterator<String> i = readAllLines(file, UTF_8).listIterator(); i.hasNext(); ) {
        String line = i.next();
        long lineNumber = i.nextIndex();
        Matcher matcher = EXPECTATION_COMMENT.matcher(line);
        ExpectedFactAssertion.Factory fact =
            matcher.matches() ? readExpectedFact(matcher.group("expectation")) : null;
        if (fact != null) {
          expectations.add(fact);
        } else {
          for (ExpectedFactAssertion.Factory expectation : expectations) {
            expectedFacts.add(expectation.create(relativeFile, lineNumber));
          }
          expectations.clear();
        }
      }
      return expectedFacts.build();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final Pattern EXPECTATION_COMMENT = Pattern.compile("\\s*// (?<expectation>.*)");

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
  public abstract static class ConformanceTestAssertion {

    private final Path file;

    protected ConformanceTestAssertion(Path file) {
      this.file = file;
    }

    /** The file path relative to the test source root. */
    public final Path getFile() {
      return file;
    }

    public static final Comparator<ConformanceTestAssertion> COMPARATOR =
        comparing(ConformanceTestAssertion::getFile)
            .thenComparing(
                cta ->
                    cta instanceof ExpectedFactAssertion
                        ? ((ExpectedFactAssertion) cta).lineNumber
                        : Long.MAX_VALUE)
            .thenComparing(ConformanceTestReport::toReportText);

    /**
     * An assertion that the tool behaves in a way consistent with a specific fact about a line in
     * the source code. Some of these facts indicate that according to the JSpecify specification,
     * the code in question may have an error that should be reported to users; other expected facts
     * are informational, such as the expected nullness-augmented type of an expression.
     */
    public abstract static class ExpectedFactAssertion extends ConformanceTestAssertion {

      /** Read an {@link ExpectedFactAssertion} from a line of either a source file or a report. */
      static @Nullable Factory readExpectedFact(String text) {
        return FACTORIES.stream()
            .map(f -> f.apply(text))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
      }

      private static final ImmutableList<Function<String, @Nullable Factory>> FACTORIES =
          ImmutableList.of(NullnessMismatch::parse, CannotConvert::parse);

      /** A factory for {@link ExpectedFactAssertion}s. */
      @FunctionalInterface
      interface Factory {

        /** Returns an {@link ExpectedFactAssertion} for a line in a file. */
        ExpectedFactAssertion create(Path file, long lineNumber);
      }

      private final long lineNumber;
      private final String commentText;

      protected ExpectedFactAssertion(Path file, long lineNumber, String commentText) {
        super(file);
        this.lineNumber = lineNumber;
        this.commentText = commentText;
      }

      /** Returns the line number of the code in the source file to which this assertion applies. */
      public long getLineNumber() {
        return lineNumber;
      }

      public String getCommentText() {
        return commentText;
      }

      /** Returns whether the given reported fact satisfies this assertion. */
      abstract boolean isSatisfied(ReportedFact reportedFact);

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || this.getClass() != o.getClass()) {
          return false;
        }
        ExpectedFactAssertion that = (ExpectedFactAssertion) o;
        return this.getFile().equals(that.getFile())
            && this.lineNumber == that.lineNumber
            && this.commentText.equals(that.commentText);
      }

      @Override
      public int hashCode() {
        return Objects.hash(getFile(), lineNumber, commentText);
      }

      public static final class NullnessMismatch extends ExpectedFactAssertion {

        static @Nullable Factory parse(String text) {
          return NULLNESS_MISMATCH.matcher(text).matches()
              ? (file, lineNumber) -> new NullnessMismatch(file, lineNumber, text)
              : null;
        }

        private static final Pattern NULLNESS_MISMATCH =
            Pattern.compile("jspecify_nullness_mismatch\\b.*");

        private NullnessMismatch(Path file, long lineNumber, String text) {
          super(file, lineNumber, text);
        }

        @Override
        boolean isSatisfied(ReportedFact reportedFact) {
          return reportedFact.matches(this);
        }
      }

      public static final class CannotConvert extends ExpectedFactAssertion {

        static @Nullable Factory parse(String text) {
          Matcher matcher = CANNOT_CONVERT.matcher(text);
          return matcher.matches()
              ? (file, lineNumber) ->
                  new CannotConvert(
                      file,
                      lineNumber,
                      text,
                      matcher.group("sourceType"),
                      matcher.group("sinkType"))
              : null;
        }

        private static final Pattern CANNOT_CONVERT =
            Pattern.compile("test:cannot-convert:(?<sourceType>.*) to (?<sinkType>.*)");

        private final String sourceType;
        private final String sinkType;

        private CannotConvert(
            Path file, long lineNumber, String text, String sourceType, String sinkType) {
          super(file, lineNumber, text);
          this.sourceType = sourceType;
          this.sinkType = sinkType;
        }

        @Override
        boolean isSatisfied(ReportedFact reportedFact) {
          return reportedFact.matches(this);
        }
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

  /** The result (pass or fail) of an {@linkplain ConformanceTestAssertion assertion}. */
  public static final class ConformanceTestResult {

    private final ConformanceTestAssertion assertion;
    private final boolean pass;
    private final ImmutableList<ReportedFact> unexpectedFacts;

    private ConformanceTestResult(
        NoUnexpectedFactsAssertion assertion, Iterable<ReportedFact> unexpectedFacts) {
      this.assertion = assertion;
      this.unexpectedFacts = ImmutableList.copyOf(unexpectedFacts);
      this.pass = this.unexpectedFacts.isEmpty();
    }

    ConformanceTestResult(ConformanceTestAssertion assertion, boolean pass) {
      this.assertion = assertion;
      this.pass = pass;
      this.unexpectedFacts = ImmutableList.of();
    }

    /** The assertion. */
    public ConformanceTestAssertion getAssertion() {
      return assertion;
    }

    /** Whether the test passed. */
    public boolean passed() {
      return pass;
    }

    /**
     * For {@link NoUnexpectedFactsAssertion} assertions, the unexpected must-report facts. Not
     * written to or read from the report file.
     */
    public ImmutableList<ReportedFact> getUnexpectedFacts() {
      return unexpectedFacts;
    }

    public static final Comparator<ConformanceTestResult> COMPARATOR =
        comparing(ConformanceTestResult::getAssertion, ConformanceTestAssertion.COMPARATOR);
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
    protected abstract boolean matches(NullnessMismatch nullnessMismatch);

    /** Returns true if this reported fact matches the given expected fact. */
    protected abstract boolean matches(CannotConvert cannotConvert);

    /** Returns the message reported, without the file name or line number. */
    @Override
    public abstract String toString();

    public static final Comparator<ReportedFact> COMPARATOR =
        comparing(ReportedFact::getFile).thenComparing(ReportedFact::getLineNumber);
  }
}
