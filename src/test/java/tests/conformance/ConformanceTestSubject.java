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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Fact.fact;
import static com.google.common.truth.Truth.assertAbout;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.truth.Fact;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.ExpectedFactAssertion;
import tests.conformance.AbstractConformanceTest.ConformanceTestResult;
import tests.conformance.AbstractConformanceTest.ReportedFact;
import tests.conformance.ConformanceTestReport.Comparison;

/** A Truth {@link Subject} for {@link ConformanceTestReport}s. */
public final class ConformanceTestSubject extends Subject {

  private final ConformanceTestReport actual;

  /** Returns the {@link Subject.Factory} for {@link ConformanceTestReport}s. */
  public static Factory<ConformanceTestSubject, ConformanceTestReport> conformanceTests() {
    return ConformanceTestSubject::new;
  }

  /** Starts a fluent assertion about a {@link ConformanceTestReport}. */
  public static ConformanceTestSubject assertThat(ConformanceTestReport report) {
    return assertAbout(conformanceTests()).that(report);
  }

  private ConformanceTestSubject(FailureMetadata failureMetadata, ConformanceTestReport actual) {
    super(failureMetadata, actual);
    this.actual = actual;
  }

  /**
   * Asserts that the actual {@link ConformanceTestReport} matches a previous report. Note that the
   * actual unexpected facts in the current report are not compared; only whether there were any
   * unexpected facts.
   */
  public void matches(ConformanceTestReport previousReport) {
    Comparison resultsVsPreviousResults = actual.compareTo(previousReport);
    if (!resultsVsPreviousResults.reportsAreEqual()) {
      failWithoutActual(
          conformanceTestFact("newly failing", resultsVsPreviousResults.brokenTests()),
          conformanceTestFact("new tests that fail", resultsVsPreviousResults.newTestsThatFail()),
          conformanceTestFact("newly fixed (good!)", resultsVsPreviousResults.fixedTests()),
          conformanceTestFact(
              "new tests that pass (good!)", resultsVsPreviousResults.newTestsThatPass()),
          conformanceTestFact("deleted tests (good!)", resultsVsPreviousResults.deletedTests()));
    }
  }

  /**
   * Asserts that all tests pass. Upon failure, emits a report of missing expected facts and any
   * unexpected facts in each file.
   */
  public void allTestsPass() {
    List<Fact> facts = new ArrayList<>();
    failuresByFile()
        .forEach(
            (file, failures) -> {
              ImmutableSet<ConformanceTestAssertion> expectedFactFailures =
                  failures.stream()
                      .map(ConformanceTestResult::getAssertion)
                      .filter(assertion -> assertion instanceof ExpectedFactAssertion)
                      .collect(toImmutableSet());
              if (!expectedFactFailures.isEmpty()) {
                facts.add(
                    fact(
                        "expected facts not found in " + file,
                        expectedFactFailures.stream()
                            .sorted(ConformanceTestAssertion.COMPARATOR)
                            .map(ConformanceTestReport::toReportText)
                            .collect(joining("\n"))));
              }
              ImmutableList<ReportedFact> unexpectedFacts =
                  failures.stream()
                      .flatMap(result -> result.getUnexpectedFacts().stream())
                      .collect(toImmutableList());
              if (!unexpectedFacts.isEmpty()) {
                facts.add(
                    fact(
                        "unexpected facts found in " + file,
                        unexpectedFacts.stream()
                            .sorted(ReportedFact.COMPARATOR)
                            .map(Object::toString)
                            .collect(joining("\n"))));
              }
            });
    if (facts.isEmpty()) {
      return;
    }
    Fact firstFact = facts.remove(0);
    failWithoutActual(firstFact, facts.toArray(new Fact[0]));
  }

  private ImmutableSortedMap<Path, ImmutableSet<ConformanceTestResult>> failuresByFile() {
    return actual.failures().stream()
        .collect(
            collectingAndThen(
                groupingBy(r -> r.getAssertion().getFile(), toImmutableSet()),
                ImmutableSortedMap::copyOf));
  }

  private static Fact conformanceTestFact(
      String key, ImmutableSet<ConformanceTestAssertion> assertions) {
    return fact(
        key,
        assertions.isEmpty()
            ? "none"
            : assertions.stream().map(Objects::toString).collect(joining("\n")));
  }
}
