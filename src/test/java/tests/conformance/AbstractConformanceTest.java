package tests.conformance;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableListMultimap.toImmutableListMultimap;
import static com.google.common.collect.Lists.partition;
import static com.google.common.collect.Sets.union;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllLines;
import static java.nio.file.Files.readString;
import static java.nio.file.Files.walk;
import static java.nio.file.Files.writeString;
import static java.util.Arrays.stream;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.ExpectedFact.readExpectedFact;

import com.google.common.base.Ascii;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Streams;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Formatter;
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

  private Matches runTests() throws IOException {
    try (Stream<Path> paths = walk(testDirectory)) {
      return paths
          .filter(path -> path.toFile().isDirectory())
          .flatMap(this::javaFileGroups)
          .flatMap(this::analyzeFiles)
          .reduce(Matches.EMPTY, Matches::combine);
    }
  }

  private Stream<Matches> analyzeFiles(List<Path> files) {
    ImmutableMap<Path, ImmutableListMultimap<Long, ReportedFact>> reportedFactsByFile =
        Streams.stream(analyze(ImmutableList.copyOf(files)))
            .collect(ReportedFact.BY_FILE_AND_LINE_NUMBER);
    return files.stream()
        .map(testDirectory::relativize)
        .map(
            file ->
                Matches.inFile(
                    file,
                    requireNonNullElse(reportedFactsByFile.get(file), ImmutableListMultimap.of()),
                    readExpectedFacts(file).stream()
                        .collect(
                            toImmutableListMultimap(
                                ExpectedFactAssertion::getLineNumber, a -> a))));
  }

  /**
   * Analyzes a nonempty set of Java source {@code files} that may refer to each other.
   *
   * @return the facts reported by the analysis
   */
  protected abstract Iterable<ReportedFact> analyze(ImmutableList<Path> files);

  static final class Matches {
    static final Matches EMPTY =
        new Matches(
            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableListMultimap.of());

    static Matches inFile(
        Path file,
        Multimap<Long, ReportedFact> reportedFactsByLine,
        Multimap<Long, ExpectedFactAssertion> expectedFactsByLine) {
      ImmutableListMultimap.Builder<ExpectedFactAssertion, ReportedFact> matches =
          ImmutableListMultimap.builder();
      for (long lineNumber : union(reportedFactsByLine.keySet(), expectedFactsByLine.keySet())) {
        for (ExpectedFactAssertion expectedFact : expectedFactsByLine.get(lineNumber)) {
          for (ReportedFact reportedFact : reportedFactsByLine.get(lineNumber)) {
            if (reportedFact.matches(expectedFact.getFact())) {
              matches.put(expectedFact, reportedFact);
            }
          }
        }
      }
      return new Matches(
          ImmutableSet.of(file),
          expectedFactsByLine.values(),
          reportedFactsByLine.values(),
          matches.build());
    }

    private final ImmutableSortedSet<Path> files;
    private final ImmutableSet<ExpectedFactAssertion> expectedFacts;
    private final ImmutableSet<ReportedFact> reportedFacts;
    private final ImmutableListMultimap<ExpectedFactAssertion, ReportedFact> matches;

    Matches(
        Collection<Path> files,
        Collection<ExpectedFactAssertion> expectedFacts,
        Collection<ReportedFact> reportedFacts,
        Multimap<ExpectedFactAssertion, ReportedFact> matches) {
      this.files = ImmutableSortedSet.copyOf(files);
      this.expectedFacts = ImmutableSet.copyOf(expectedFacts);
      this.reportedFacts = ImmutableSet.copyOf(reportedFacts);
      this.matches = ImmutableListMultimap.copyOf(matches);
    }

    private static Matches combine(Matches left, Matches right) {
      return new Matches(
          union(left.files, right.files),
          union(left.expectedFacts, right.expectedFacts),
          union(left.reportedFacts, right.reportedFacts),
          ImmutableListMultimap.copyOf(
              Stream.concat(left.matches.entries().stream(), right.matches.entries().stream())
                  .collect(toList())));
    }

    boolean isReported(ExpectedFactAssertion expectedFact) {
      return matches.containsKey(expectedFact);
    }

    boolean isExpected(ReportedFact reportedFact) {
      return matches.containsValue(reportedFact);
    }

    String report(boolean details) {
      ImmutableListMultimap<Path, ExpectedFactAssertion> expectedFactsByFile =
          expectedFacts.stream()
              .collect(toImmutableListMultimap(ExpectedFactAssertion::getFile, efa -> efa));
      ImmutableListMultimap<Path, ReportedFact> reportedFactsByFile =
          reportedFacts.stream().collect(toImmutableListMultimap(ReportedFact::getFile, rf -> rf));
      long passes =
          expectedFacts.stream().filter(this::isReported).count()
              + files.stream()
                  .filter(
                      f ->
                          reportedFactsByFile.get(f).stream()
                              .noneMatch(rf -> rf.mustBeExpected() && !isExpected(rf)))
                  .count();
      long fails =
          expectedFacts.stream().filter(efa -> !isReported(efa)).count()
              + files.stream()
                  .filter(
                      f ->
                          reportedFactsByFile.get(f).stream()
                              .anyMatch(rf -> rf.mustBeExpected() && !isExpected(rf)))
                  .count();
      Formatter report = new Formatter();
      report.format(
          "# %d pass; %d fail; %d total; %.1f%% score%n",
          passes, fails, passes + fails, 100.0 * passes / (passes + fails));
      for (Path file : files) {
        ImmutableListMultimap<Long, ExpectedFactAssertion> expectedFactsInFile =
            expectedFactsByFile.get(file).stream()
                .collect(toImmutableListMultimap(ExpectedFactAssertion::getLineNumber, efa -> efa));
        ImmutableListMultimap<Long, ReportedFact> reportedFactsInFile =
            reportedFactsByFile.get(file).stream()
                .collect(toImmutableListMultimap(ReportedFact::getLineNumber, rf -> rf));
        for (long lineNumber :
            ImmutableSortedSet.copyOf(
                union(expectedFactsInFile.keySet(), reportedFactsInFile.keySet()))) {
          for (ExpectedFactAssertion expectedFact : expectedFactsInFile.get(lineNumber)) {
            report.format(
                "%s: %s:%d %s%n",
                isReported(expectedFact) ? "PASS" : "FAIL",
                file,
                lineNumber,
                expectedFact.getFact().commentText());
          }
          if (details) {
            for (ReportedFact reportedFact : reportedFactsInFile.get(lineNumber)) {
              if (!isExpected(reportedFact)) {
                report.format(
                    "%s: %s:%d %s%n",
                    reportedFact.mustBeExpected() ? "OOPS" : "INFO",
                    file,
                    lineNumber,
                    reportedFact.expectedFact() != null
                        ? reportedFact.expectedFact().commentText()
                        : reportedFact.toString());
              }
            }
          }
        }
        if (!details) {
          report.format(
              "%s: %s: no unexpected facts%n",
              reportedFactsByFile.get(file).stream()
                      .noneMatch(rf -> rf.mustBeExpected() && !isExpected(rf))
                  ? "PASS"
                  : "FAIL",
              file);
        }
      }
      return report.toString();
    }
  }

  /** Reads {@link ExpectedFactAssertion}s from comments in a file. */
  private ImmutableList<ExpectedFactAssertion> readExpectedFacts(Path relativeFile) {
    try {
      ImmutableList.Builder<ExpectedFactAssertion> expectedFactAssertions = ImmutableList.builder();
      List<ExpectedFact> expectedFacts = new ArrayList<>();
      for (ListIterator<String> i =
              readAllLines(testDirectory.resolve(relativeFile), UTF_8).listIterator();
          i.hasNext(); ) {
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
    Matches matches = runTests();
    switch (Mode.fromEnvironment()) {
      case DETAILS:
        System.out.print(matches.report(true));
        // fall-through

      case COMPARE:
        assertThat(matches.report(false)).isEqualTo(readString(testReport, UTF_8));
        break;

      case WRITE:
        writeString(testReport, matches.report(false), UTF_8);
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
   * An assertion about a test source file.
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
            .thenComparing(ConformanceTestAssertion::toReportText);

    private static String toReportText(ConformanceTestAssertion assertion) {
      StringBuilder string = new StringBuilder().append(assertion.getFile()).append(":");
      if (assertion instanceof ExpectedFactAssertion) {
        ExpectedFactAssertion expectedFact = (ExpectedFactAssertion) assertion;
        string
            .append(expectedFact.getLineNumber())
            .append(" ")
            .append(expectedFact.getFact().commentText());
      } else if (assertion instanceof NoUnexpectedFactsAssertion) {
        string.append(" no unexpected facts");
      } else {
        throw new AssertionError("unexpected assertion class: " + assertion);
      }
      return string.toString();
    }

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

    static final Collector<
            ReportedFact, ?, ImmutableMap<Path, ImmutableListMultimap<Long, ReportedFact>>>
        BY_FILE_AND_LINE_NUMBER =
            collectingAndThen(
                groupingBy(
                    ReportedFact::getFile,
                    toImmutableListMultimap(ReportedFact::getLineNumber, rf -> rf)),
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
}
}
