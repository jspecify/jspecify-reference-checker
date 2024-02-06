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

import static com.google.common.collect.Maps.immutableEntry;
import static com.google.common.collect.Multimaps.index;
import static com.google.common.collect.Sets.union;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllLines;
import static java.util.Comparator.comparingLong;
import static java.util.function.Predicate.not;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.ListMultimap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Formatter;
import java.util.stream.Stream;

/**
 * Represents the result of running {@link ConformanceTestRunner#runTests(Path, ImmutableList)} on a
 * set of files.
 */
public final class ConformanceTestReport {

  private final ImmutableSortedSet<Path> files;
  private final ImmutableListMultimap<Path, ExpectedFact> expectedFactsByFile;
  private final ImmutableListMultimap<Path, ReportedFact> reportedFactsByFile;
  private final ImmutableListMultimap<ExpectedFact, ReportedFact> matchingFacts;

  private ConformanceTestReport(
      ImmutableSortedSet<Path> files,
      ImmutableListMultimap<Path, ExpectedFact> expectedFacts,
      ImmutableListMultimap<Path, ReportedFact> reportedFacts,
      ImmutableListMultimap<ExpectedFact, ReportedFact> matchingFacts) {
    this.files = files;
    this.expectedFactsByFile = expectedFacts;
    this.reportedFactsByFile = reportedFacts;
    this.matchingFacts = matchingFacts;
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
        "%s: %s:%s:%s%n", status, fact.getFile(), fact.getIdentifier(), fact.getFactText());
  }

  /** A builder for {@link ConformanceTestReport}s. */
  static class Builder {
    private final ImmutableSortedSet.Builder<Path> files = ImmutableSortedSet.naturalOrder();
    private final ListMultimap<Path, ReportedFact> reportedFacts = ArrayListMultimap.create();
    private final ImmutableList.Builder<ExpectedFact> expectedFacts = ImmutableList.builder();
    private final ImmutableListMultimap.Builder<ExpectedFact, ReportedFact> matchingFacts =
        ImmutableListMultimap.builder();
    private final ExpectedFact.Reader expectedFactReader = new ExpectedFact.Reader();
    private final Path testDirectory;

    /**
     * Creates a builder.
     *
     * @param testDirectory the directory containing all {@linkplain #addFiles(Iterable, Iterable)
     *     files} with expected facts
     */
    Builder(Path testDirectory) {
      this.testDirectory = testDirectory;
    }

    /** Adds test files and the facts reported for them. */
    void addFiles(Iterable<Path> files, Iterable<ReportedFact> reportedFacts) {
      for (ReportedFact reportedFact : reportedFacts) {
        this.reportedFacts.put(reportedFact.getFile(), reportedFact);
      }
      for (Path file : files) {
        Path relativeFile = testDirectory.relativize(file);
        this.files.add(relativeFile);
        addExpectedFacts(relativeFile);
      }
    }

    private void addExpectedFacts(Path relativeFile) {
      try {
        ImmutableList<ExpectedFact> expectedFactsInFile =
            expectedFactReader.readExpectedFacts(
                relativeFile, readAllLines(testDirectory.resolve(relativeFile), UTF_8));
        expectedFacts.addAll(expectedFactsInFile);
        matchFacts(relativeFile, expectedFactsInFile).forEach(matchingFacts::put);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    private Stream<ImmutableMap.Entry<ExpectedFact, ReportedFact>> matchFacts(
        Path file, ImmutableList<ExpectedFact> expectedFactsInFile) {
      ImmutableListMultimap<Long, ReportedFact> reportedFactsByLine =
          index(reportedFacts.get(file), ReportedFact::getLineNumber);
      return expectedFactsInFile.stream()
          .flatMap(
              expectedFact ->
                  reportedFactsByLine.get(expectedFact.getLineNumber()).stream()
                      .filter(reportedFact -> reportedFact.matches(expectedFact))
                      .map(reportedFact -> immutableEntry(expectedFact, reportedFact)));
    }

    /** Builds the report. */
    ConformanceTestReport build() {
      expectedFactReader.checkErrors();
      return new ConformanceTestReport(
          files.build(),
          index(expectedFacts.build(), Fact::getFile),
          ImmutableListMultimap.copyOf(reportedFacts),
          matchingFacts.build());
    }
  }
}
