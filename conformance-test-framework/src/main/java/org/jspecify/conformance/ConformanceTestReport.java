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

import static com.google.common.collect.ImmutableListMultimap.flatteningToImmutableListMultimap;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Multimaps.index;
import static com.google.common.collect.Sets.union;
import static java.util.Comparator.comparingLong;
import static java.util.function.Predicate.not;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Formatter;
import org.jspecify.conformance.ConformanceTestRunner.ExpectedFact;
import org.jspecify.conformance.ConformanceTestRunner.Fact;
import org.jspecify.conformance.ConformanceTestRunner.ReportedFact;

/** Represents the results of running an {@link ConformanceTestRunner} on a set of files. */
public final class ConformanceTestReport {
  /** An empty report. */
  static final ConformanceTestReport EMPTY =
      new ConformanceTestReport(
          ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableListMultimap.of());

  /** Creates a report for a file. */
  static ConformanceTestReport forFile(
      Path file,
      ImmutableList<ReportedFact> reportedFacts,
      ImmutableList<ExpectedFact> expectedFacts) {
    ImmutableListMultimap<Long, ReportedFact> reportedFactsByLine =
        index(reportedFacts, ReportedFact::getLineNumber);
    ImmutableListMultimap<ExpectedFact, ReportedFact> matchingFacts =
        expectedFacts.stream()
            .collect(
                flatteningToImmutableListMultimap(
                    f -> f,
                    expectedFact ->
                        reportedFactsByLine.get(expectedFact.getLineNumber()).stream()
                            .filter(
                                reportedFact -> reportedFact.matches(expectedFact.getFactText()))));
    return new ConformanceTestReport(
        ImmutableSortedSet.of(file), expectedFacts, reportedFacts, matchingFacts);
  }

  /** Combines two reports into one. */
  static ConformanceTestReport combine(ConformanceTestReport left, ConformanceTestReport right) {
    return new ConformanceTestReport(
        union(left.files, right.files),
        concat(left.expectedFactsByFile.values(), right.expectedFactsByFile.values()),
        concat(left.reportedFactsByFile.values(), right.reportedFactsByFile.values()),
        ImmutableListMultimap.copyOf(
            concat(left.matchingFacts.entries(), right.matchingFacts.entries())));
  }

  private final ImmutableSortedSet<Path> files;
  private final ImmutableListMultimap<Path, ExpectedFact> expectedFactsByFile;
  private final ImmutableListMultimap<Path, ReportedFact> reportedFactsByFile;
  private final ImmutableListMultimap<ExpectedFact, ReportedFact> matchingFacts;

  private ConformanceTestReport(
      Collection<Path> files, // Path unfortunately implements Iterable<Path>.
      Iterable<ExpectedFact> expectedFacts,
      Iterable<ReportedFact> reportedFacts,
      Multimap<ExpectedFact, ReportedFact> matchingFacts) {
    this.files = ImmutableSortedSet.copyOf(files);
    this.expectedFactsByFile = index(expectedFacts, Fact::getFile);
    this.reportedFactsByFile = index(reportedFacts, Fact::getFile);
    this.matchingFacts = ImmutableListMultimap.copyOf(matchingFacts);
  }

  /**
   * Returns a textual report showing all expected facts, and whether each was reported, and
   * information about unexpected facts
   *
   * @param details if {@code true}, shows each unexpected fact, and whether it must be expected; if
   *     {@code false}, shows for each file whether there were any unexpected facts that must be
   *     expected
   */
  public String report(boolean details) {
    Formatter report = new Formatter();
    long fails = getFails();
    int total = getTotal();
    long passes = total - fails;
    report.format(
        "# %,d pass; %,d fail; %,d total; %.1f%% score%n",
        passes, fails, total, 100.0 * passes / total);
    for (Path file : files) {
      ImmutableListMultimap<Long, ExpectedFact> expectedFactsInFile =
          index(expectedFactsByFile.get(file), Fact::getLineNumber);
      ImmutableListMultimap<Long, ReportedFact> reportedFactsInFile =
          index(reportedFactsByFile.get(file), Fact::getLineNumber);
      for (long lineNumber :
          ImmutableSortedSet.copyOf(
              union(expectedFactsInFile.keySet(), reportedFactsInFile.keySet()))) {
        // Report all expected facts on this line and whether they're reported or not.
        expectedFactsInFile.get(lineNumber).stream()
            .sorted(comparingLong(ExpectedFact::getFactLineNumber))
            .forEach(
                expectedFact ->
                    writeFact(
                        report, expectedFact, matchesReportedFact(expectedFact) ? "PASS" : "FAIL"));
        if (details) {
          // Report all unexpected facts on this line and whether they must be expected or not.
          for (ReportedFact reportedFact : reportedFactsInFile.get(lineNumber)) {
            if (isUnexpected(reportedFact)) {
              writeFact(report, reportedFact, reportedFact.mustBeExpected() ? "OOPS" : "INFO");
            }
          }
        }
      }
      if (!details) {
        // Report whether the file has any unexpected reported facts that must be expected.
        report.format(
            "%s: %s: no unexpected facts%n",
            hasUnexpectedFacts(reportedFactsInFile.values()) ? "FAIL" : "PASS", file);
      }
    }
    return report.toString();
  }

  private long getFails() {
    return expectedFactsByFile.values().stream().filter(not(this::matchesReportedFact)).count()
        + files.stream().map(reportedFactsByFile::get).filter(this::hasUnexpectedFacts).count();
  }

  private int getTotal() {
    return expectedFactsByFile.size() + files.size();
  }

  private boolean hasUnexpectedFacts(Collection<ReportedFact> reportedFacts) {
    return reportedFacts.stream().anyMatch(rf -> rf.mustBeExpected() && isUnexpected(rf));
  }

  private boolean matchesReportedFact(ExpectedFact expectedFact) {
    return matchingFacts.containsKey(expectedFact);
  }

  private boolean isUnexpected(ReportedFact reportedFact) {
    return !matchingFacts.containsValue(reportedFact);
  }

  private static void writeFact(Formatter report, Fact fact, String status) {
    report.format(
        "%s: %s:%d %s%n", status, fact.getFile(), fact.getLineNumber(), fact.getFactText());
  }
}
