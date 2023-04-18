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

package tests.conformance;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static com.google.common.collect.Maps.filterValues;
import static com.google.common.collect.Streams.stream;
import static com.google.common.io.Files.asCharSink;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.lines;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Stream.concat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.ExpectedFactAssertion.readExpectedFact;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tests.ConformanceTest;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.ExpectedFactAssertion;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.NoUnexpectedFactsAssertion;
import tests.conformance.AbstractConformanceTest.ReportedFact;

/** Represents the results of running {@link ConformanceTest} on a set of files. */
public final class ConformanceTestReport {

  /**
   * Reads a previously-written report file. Note that actual unexpected facts are never written to
   * the report; only whether there were any unexpected facts.
   */
  // TODO(dpb): Should more details about the unexpected facts be recorded so that the test can note
  // when they change? Maybe just the lines with unexpected facts?
  static ConformanceTestReport readFile(Path testReport) throws IOException {
    try (Stream<String> lines = lines(testReport)) {
      return lines
          .filter(line -> !line.startsWith("#"))
          .map(
              line -> {
                Matcher matcher = REPORT_LINE.matcher(line);
                assertTrue("cannot parse line: " + line, matcher.matches());
                boolean pass = matcher.group("result").equals("PASS");
                Path file = Paths.get(matcher.group("path"));
                String expect = matcher.group("expect");
                if (expect != null) {
                  int lineNumber = Integer.parseInt(matcher.group("line"));
                  ExpectedFactAssertion.Factory fact = readExpectedFact(expect);
                  assertNotNull("cannot parse expectation: " + expect, fact);
                  return new ConformanceTestResult(fact.create(file, lineNumber), pass);
                } else {
                  return new ConformanceTestResult(new NoUnexpectedFactsAssertion(file), pass);
                }
              })
          .collect(collectingAndThen(toImmutableSet(), ConformanceTestReport::new));
    } catch (NoSuchFileException e) {
      return new ConformanceTestReport(ImmutableSet.of());
    }
  }

  private static final Pattern REPORT_LINE =
      Pattern.compile(
          "(?<result>PASS|FAIL): (?<path>\\S+\\.java):"
              + "((?<line>\\d+) (?<expect>.*)| no unexpected facts)");

  private static String toReportLine(ConformanceTestResult result) {
    return String.format(
        "%s: %s", result.passed() ? "PASS" : "FAIL", toReportText(result.getAssertion()));
  }

  static String toReportText(ConformanceTestAssertion assertion) {
    StringBuilder string = new StringBuilder().append(assertion.getFile()).append(":");
    if (assertion instanceof ExpectedFactAssertion) {
      ExpectedFactAssertion expectedFact = (ExpectedFactAssertion) assertion;
      string.append(expectedFact.getLineNumber()).append(" ").append(expectedFact.getCommentText());
    } else if (assertion instanceof NoUnexpectedFactsAssertion) {
      string.append(" no unexpected facts");
    } else {
      throw new AssertionError("unexpected assertion class: " + assertion);
    }
    return string.toString();
  }

  private final ImmutableSet<ConformanceTestResult> results;

  ConformanceTestReport(Iterable<ConformanceTestResult> results) {
    this.results = ImmutableSet.copyOf(results);
  }

  /** Compares this report to a report read from a file. */
  public Comparison compareTo(ConformanceTestReport previousResults) {
    return new Comparison(results, previousResults.results);
  }

  /** Returns the failing assertions. */
  public ImmutableSet<ConformanceTestResult> failures() {
    return results.stream().filter(result -> !result.passed()).collect(toImmutableSet());
  }

  /**
   * Writes the report to a file. Note that actual unexpected facts are never written to the report;
   * only whether there were any unexpected facts.
   */
  public void writeFile(Path file) throws IOException {
    asCharSink(file.toFile(), UTF_8).writeLines(concat(reportHeader(), reportBody()));
  }

  private Stream<String> reportHeader() {
    int fails = failures().size();
    int passes = results.size() - fails;
    return Stream.of(
        String.format(
            "# %d pass; %d fail; %d total; %.1f%% score",
            passes, fails, results.size(), 100.0 * passes / results.size()));
  }

  private Stream<String> reportBody() {
    return results.stream()
        .sorted(ConformanceTestResult.COMPARATOR)
        .map(ConformanceTestReport::toReportLine);
  }

  /** A comparison between a current test report and a previous report loaded from a file. */
  public static final class Comparison {

    private final MapDifference<ConformanceTestAssertion, Boolean> newVsOld;

    private Comparison(
        Iterable<ConformanceTestResult> results, Iterable<ConformanceTestResult> previousResults) {
      this.newVsOld = Maps.difference(resultsMap(results), resultsMap(previousResults));
    }

    /** Returns the failing assertions that had previously passed. */
    public ImmutableSortedSet<ConformanceTestAssertion> brokenTests() {
      return ImmutableSortedSet.copyOf(
          filterValues(newVsOld.entriesDiffering(), vd -> !vd.leftValue() && vd.rightValue())
              .keySet());
    }

    /** Returns the failing assertions that previously didn't exist. */
    public ImmutableSortedSet<ConformanceTestAssertion> newTestsThatFail() {
      return ImmutableSortedSet.copyOf(
          filterValues(newVsOld.entriesOnlyOnLeft(), pass -> !pass).keySet());
    }

    /** Returns the passing assertions that previously failed. */
    public ImmutableSortedSet<ConformanceTestAssertion> fixedTests() {
      return ImmutableSortedSet.copyOf(
          filterValues(newVsOld.entriesDiffering(), vd -> vd.leftValue() && !vd.rightValue())
              .keySet());
    }

    /** Returns the passing assertions that previously didn't exist. */
    public ImmutableSortedSet<ConformanceTestAssertion> newTestsThatPass() {
      return ImmutableSortedSet.copyOf(
          filterValues(newVsOld.entriesOnlyOnLeft(), pass -> pass).keySet());
    }

    /** Returns the assertions that no longer exist. */
    public ImmutableSortedSet<ConformanceTestAssertion> deletedTests() {
      return ImmutableSortedSet.copyOf(newVsOld.entriesOnlyOnRight().keySet());
    }

    /**
     * Returns {@code true} if the current report matches the previous report. Note that the actual
     * unexpected facts in the current report are not compared; only whether there were any
     * unexpected facts.
     */
    public boolean reportsAreEqual() {
      return newVsOld.areEqual();
    }

    private static ImmutableSortedMap<ConformanceTestAssertion, Boolean> resultsMap(
        Iterable<ConformanceTestResult> results) {
      return stream(results)
          .collect(
              toImmutableSortedMap(
                  ConformanceTestAssertion::compareTo,
                  ConformanceTestResult::getAssertion,
                  ConformanceTestResult::passed));
    }
  }

  /** The result (pass or fail) of an {@linkplain ConformanceTestAssertion assertion}. */
  public static final class ConformanceTestResult {
    private final ConformanceTestAssertion assertion;
    private final boolean pass;
    private final ImmutableList<ReportedFact> unexpectedFacts;

    ConformanceTestResult(
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
        comparing(ConformanceTestResult::getAssertion, ConformanceTestAssertion::compareTo);
  }
}
