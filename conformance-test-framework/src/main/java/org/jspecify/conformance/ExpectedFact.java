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
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

  private final String factText;
  private final long factLineNumber;

  ExpectedFact(Path file, long lineNumber, String factText, long factLineNumber) {
    super(file, lineNumber);
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
        && this.factText.equals(that.factText)
        && this.factLineNumber == that.factLineNumber;
  }

  @Override
  public int hashCode() {
    return Objects.hash(getFile(), getLineNumber(), factText);
  }

  @Override
  public String toString() {
    return toStringHelper(this)
        .add("file", getFile())
        .add("lineNumber", getLineNumber())
        .add("factText", factText)
        .add("factLineNumber", factLineNumber)
        .toString();
  }

  /** Reads {@link ExpectedFact}s from comments in a file. */
  static class Reader {

    private static final Pattern EXPECTATION_COMMENT =
        Pattern.compile(
            "\\s*// "
                + ("(?<fact>"
                    + FACT_PATTERNS.stream().map(Pattern::pattern).collect(joining("|"))
                    + ")")
                + "\\s*");

    /** Reads expected facts from lines in a file. */
    ImmutableList<ExpectedFact> readExpectedFacts(Path file, List<String> lines) {
      ImmutableList.Builder<ExpectedFact> expectedFacts = ImmutableList.builder();
      Map<Long, String> facts = new HashMap<>();
      ListIterator<String> i = lines.listIterator();
      while (i.hasNext()) {
        String line = i.next();
        long lineNumber = i.nextIndex();
        Matcher matcher = EXPECTATION_COMMENT.matcher(line);
        if (matcher.matches()) {
          String expectation = matcher.group("fact");
          if (expectation != null) {
            facts.put(lineNumber, expectation.trim());
          }
        } else {
          facts.forEach(
              (factLineNumber, factText) ->
                  expectedFacts.add(new ExpectedFact(file, lineNumber, factText, factLineNumber)));
          facts.clear();
        }
      }
      // TODO(netdpb): Report an error if facts is not empty.
      return expectedFacts.build();
    }
  }
}
