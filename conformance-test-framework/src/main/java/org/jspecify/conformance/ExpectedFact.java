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

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;
import static java.util.stream.Collectors.joining;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * An assertion that the tool behaves in a way consistent with a specific fact about a line in the
 * source code. Some of these facts indicate that according to the JSpecify specification, the code
 * in question may have an error that should be reported to users; other expected facts are
 * informational, such as the expected nullness-augmented type of an expression.
 */
public final class ExpectedFact extends Fact {

  private static final Pattern NULLNESS_MISMATCH =
      Pattern.compile("jspecify_nullness_mismatch\\b.*");

  private static final ImmutableList<Pattern> FACT_PATTERNS =
      ImmutableList.of(
          NULLNESS_MISMATCH,
          // TODO: wildcard types have whitespace
          Pattern.compile("test:cannot-convert:\\S+ to \\S+"),
          Pattern.compile("test:expression-type:[^:]+:.*"),
          Pattern.compile("test:irrelevant-annotation:\\S+"),
          Pattern.compile("test:sink-type:[^:]+:.*"));

  private final @Nullable String testName;
  private final String factText;
  private final long factLineNumber;

  ExpectedFact(
      Path file, long lineNumber, @Nullable String testName, String factText, long factLineNumber) {
    super(file, lineNumber);
    this.testName = testName;
    this.factText = factText;
    this.factLineNumber = factLineNumber;
  }

  /**
   * Returns {@code true} if {@code fact} is a legacy {@code jspecify_nullness_mismatch} assertion.
   */
  public boolean isNullnessMismatch() {
    return NULLNESS_MISMATCH.matcher(getFactText()).matches();
  }

  @Override
  protected String getFactText() {
    return factText;
  }

  /** Returns the line number in the input file where the expected fact is. */
  long getFactLineNumber() {
    return factLineNumber;
  }

  @Override
  public String getIdentifier() {
    return testName == null ? super.getIdentifier() : testName;
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
        && Objects.equals(this.testName, that.testName)
        && this.factText.equals(that.factText)
        && this.factLineNumber == that.factLineNumber;
  }

  @Override
  public int hashCode() {
    return Objects.hash(getFile(), getLineNumber(), testName, factText);
  }

  @Override
  public String toString() {
    return toStringHelper(this)
        .add("file", getFile())
        .add("testName", testName)
        .add("lineNumber", getLineNumber())
        .add("factText", factText)
        .add("factLineNumber", factLineNumber)
        .toString();
  }

  /** Reads {@link ExpectedFact}s from comments in a file. */
  static class Reader {

    private static final Pattern EXPECTATION_COMMENT =
        Pattern.compile(
            "\\s*// ("
                + "(test:name:(?<testName>.*))"
                + "|"
                + ("(?<fact>"
                    + FACT_PATTERNS.stream().map(Pattern::pattern).collect(joining("|"))
                    + ")")
                + ")\\s*");

    private static final CharMatcher ASCII_DIGIT = CharMatcher.inRange('0', '9');

    private final Map<Long, String> facts = new HashMap<>();
    private final List<String> errors = new ArrayList<>();

    private Path file;
    private @Nullable String testName;
    private long lineNumber;

    /** Reads expected facts from lines in a file. */
    ImmutableList<ExpectedFact> readExpectedFacts(Path file, List<String> lines) {
      this.file = file;
      ImmutableList.Builder<ExpectedFact> expectedFacts = ImmutableList.builder();
      ListIterator<String> i = lines.listIterator();
      while (i.hasNext()) {
        String line = i.next();
        lineNumber = i.nextIndex();
        Matcher matcher = EXPECTATION_COMMENT.matcher(line);
        if (matcher.matches()) {
          setTestName(matcher.group("testName"));
          String fact = matcher.group("fact");
          if (fact != null) {
            facts.put(lineNumber, fact.trim());
          }
        } else {
          if (testName != null) {
            check(!facts.isEmpty(), "no expected facts for test named %s", testName);
          }
          facts.forEach(
              (factLineNumber, factText) ->
                  expectedFacts.add(
                      new ExpectedFact(file, lineNumber, testName, factText, factLineNumber)));
          facts.clear();
          testName = null;
        }
      }
      return checkUniqueTestNames(expectedFacts.build());
    }

    private void setTestName(@Nullable String testName) {
      if (testName == null) {
        return;
      }
      check(this.testName == null, "test name already set");
      check(facts.isEmpty(), "test name must come before assertions for a line");
      this.testName = checkTestName(testName.trim());
    }

    private boolean check(boolean test, String format, Object... args) {
      if (!test) {
        errors.add(String.format("  %s:%d: %s", file, lineNumber, String.format(format, args)));
      }
      return test;
    }

    private String checkTestName(String testName) {
      if (check(!testName.isEmpty(), "test name cannot be empty")) {
        check(!testName.contains(":"), "test name cannot contain a colon: %s", testName);
        check(!ASCII_DIGIT.matchesAllOf(testName), "test name cannot be an integer: %s", testName);
      }
      return testName;
    }

    private ImmutableList<ExpectedFact> checkUniqueTestNames(
        ImmutableList<ExpectedFact> expectedFacts) {
      expectedFacts.stream()
          .filter(ef -> ef.testName != null)
          .collect(toImmutableSetMultimap(ef -> ef.testName, ExpectedFact::getLineNumber))
          .asMap()
          .forEach(
              (testName, lineNumbers) ->
                  check(
                      lineNumbers.size() == 1,
                      "test name not unique: test '%s' appears on tests of lines %s",
                      testName,
                      lineNumbers));
      return expectedFacts;
    }

    /** Throws if there were any errors encountered while reading expected facts. */
    void checkErrors() {
      checkArgument(errors.isEmpty(), "errors in test inputs:\n%s", Joiner.on('\n').join(errors));
    }
  }
}
