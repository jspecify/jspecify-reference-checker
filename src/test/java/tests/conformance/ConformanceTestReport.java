package tests.conformance;

import static com.google.common.collect.ImmutableListMultimap.flatteningToImmutableListMultimap;
import static com.google.common.collect.ImmutableListMultimap.toImmutableListMultimap;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Multimaps.index;
import static com.google.common.collect.Sets.union;
import static java.util.Comparator.comparingLong;
import static java.util.function.Function.identity;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import java.nio.file.Path;
import java.util.Formatter;
import tests.conformance.AbstractConformanceTest.ExpectedFactAssertion;
import tests.conformance.AbstractConformanceTest.Fact;
import tests.conformance.AbstractConformanceTest.ReportedFact;

/** A report showing expected facts and reported facts. */
final class ConformanceTestReport {
  /** An empty report. */
  static final ConformanceTestReport EMPTY =
      new ConformanceTestReport(
          ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableListMultimap.of());

  /** Creates a report for a file. */
  static ConformanceTestReport forFile(
      Path file,
      ImmutableList<ReportedFact> reportedFacts,
      ImmutableList<ExpectedFactAssertion> expectedFacts) {
    ImmutableListMultimap<Long, ReportedFact> reportedFactsByLine =
        reportedFacts.stream()
            .collect(toImmutableListMultimap(ReportedFact::getLineNumber, rf -> rf));
    ImmutableListMultimap<ExpectedFactAssertion, ReportedFact> matchingFacts =
        expectedFacts.stream()
            .sorted(comparingLong(ExpectedFactAssertion::getLineNumber))
            .collect(
                flatteningToImmutableListMultimap(
                    identity(),
                    expectedFact ->
                        reportedFactsByLine.get(expectedFact.getLineNumber()).stream()
                            .filter(reportedFact -> reportedFact.matches(expectedFact.getFact()))));
    return new ConformanceTestReport(
        ImmutableSet.of(file), expectedFacts, reportedFacts, matchingFacts);
  }

  /** Combines two reports into one. */
  static ConformanceTestReport combine(ConformanceTestReport left, ConformanceTestReport right) {
    return new ConformanceTestReport(
        concat(left.files, right.files),
        concat(left.expectedFactsByFile.values(), right.expectedFactsByFile.values()),
        concat(left.reportedFactsByFile.values(), right.reportedFactsByFile.values()),
        ImmutableListMultimap.copyOf(
            concat(left.matchingFacts.entries(), right.matchingFacts.entries())));
  }

  private final ImmutableSortedSet<Path> files;
  private final ImmutableListMultimap<Path, ExpectedFactAssertion> expectedFactsByFile;
  private final ImmutableListMultimap<Path, ReportedFact> reportedFactsByFile;
  private final ImmutableListMultimap<ExpectedFactAssertion, ReportedFact> matchingFacts;

  private ConformanceTestReport(
      Iterable<Path> files,
      Iterable<ExpectedFactAssertion> expectedFacts,
      Iterable<ReportedFact> reportedFacts,
      Multimap<ExpectedFactAssertion, ReportedFact> matchingFacts) {
    this.files =
        files instanceof Path
            ? ImmutableSortedSet.of((Path) files)
            : ImmutableSortedSet.copyOf(files);
    this.expectedFactsByFile = index(expectedFacts, ExpectedFactAssertion::getFile);
    this.reportedFactsByFile = index(reportedFacts, ReportedFact::getFile);
    this.matchingFacts = ImmutableListMultimap.copyOf(matchingFacts);
  }

  /** Returns {@code true} if a matching fact was reported. */
  public boolean isReported(ExpectedFactAssertion expectedFact) {
    return matchingFacts.containsKey(expectedFact);
  }

  /** Returns {@code true} if this fact was expected. */
  boolean isExpected(ReportedFact reportedFact) {
    return matchingFacts.containsValue(reportedFact);
  }

  /**
   * Returns a textual report showing all expected facts, and whether each was reported, and
   * information about unexpected facts
   *
   * @param details if {@code true}, shows each unexpected fact, and whether it must be expected; if
   *     {@code false}, shows for each file whether there were any unexpected facts that must be
   *     expected
   */
  String report(boolean details) {
    Formatter report = new Formatter();
    report.format(
        "# %d pass; %d fail; %d total; %.1f%% score%n",
        getPasses(), getFails(), getTotal(), 100.0 * getPasses() / getTotal());
    for (Path file : files) {
      ImmutableListMultimap<Long, ExpectedFactAssertion> expectedFactsInFile =
          index(expectedFactsByFile.get(file), Fact::getLineNumber);
      ImmutableListMultimap<Long, ReportedFact> reportedFactsInFile =
          index(reportedFactsByFile.get(file), Fact::getLineNumber);
      for (long lineNumber :
          ImmutableSortedSet.copyOf(
              union(expectedFactsInFile.keySet(), reportedFactsInFile.keySet()))) {
        for (ExpectedFactAssertion expectedFact : expectedFactsInFile.get(lineNumber)) {
          writeFact(report, expectedFact, isReported(expectedFact) ? "PASS" : "FAIL");
        }
        if (details) {
          for (ReportedFact reportedFact : reportedFactsInFile.get(lineNumber)) {
            if (!isExpected(reportedFact)) {
              writeFact(report, reportedFact, reportedFact.mustBeExpected() ? "OOPS" : "INFO");
            }
          }
        }
      }
      if (!details) {
        report.format(
            "%s: %s: no unexpected facts%n",
            reportedFactsInFile.values().stream()
                    .noneMatch(rf -> rf.mustBeExpected() && !isExpected(rf))
                ? "PASS"
                : "FAIL",
            file);
      }
    }
    return report.toString();
  }

  private long getFails() {
    return expectedFactsByFile.values().stream().filter(efa -> !isReported(efa)).count()
        + files.stream()
            .filter(
                f ->
                    reportedFactsByFile.get(f).stream()
                        .anyMatch(rf -> rf.mustBeExpected() && !isExpected(rf)))
            .count();
  }

  private long getPasses() {
    return expectedFactsByFile.values().stream().filter(this::isReported).count()
        + files.stream()
            .filter(
                f ->
                    reportedFactsByFile.get(f).stream()
                        .noneMatch(rf -> rf.mustBeExpected() && !isExpected(rf)))
            .count();
  }

  private int getTotal() {
    return expectedFactsByFile.size() + files.size();
  }

  private static void writeFact(Formatter report, Fact fact, String status) {
    report.format(
        "%s: %s:%d %s%n", status, fact.getFile(), fact.getLineNumber(), fact.getFactText());
  }
}
