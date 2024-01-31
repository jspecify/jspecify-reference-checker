/*
 * Copyright 2024 The JSpecify Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jspecify.conformance;

import static com.google.common.truth.Truth.assertThat;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ExpectedFactTest {

  private static final Path FILE = Paths.get("path/to/File.java");

  private final ExpectedFact.Reader reader = new ExpectedFact.Reader();

  private ImmutableList<ExpectedFact> readExpectedFacts(String... lines) {
    return reader.readExpectedFacts(FILE, asList(lines));
  }

  @Test
  public void readExpectedFacts_lineNumber() {
    assertThat(
            readExpectedFacts(
                "// jspecify_nullness_mismatch ",
                "// jspecify_nullness_mismatch plus_other_stuff ",
                "line under test",
                "// test:cannot-convert:type1 to type2 ",
                "// test:expression-type:type2:expr2 ",
                "// test:sink-type:type1:expr1 ",
                "another line under test",
                "// test:irrelevant-annotation:@Nullable ",
                "yet another line under test"))
        .containsExactly(
            new ExpectedFact(FILE, 3, null, "jspecify_nullness_mismatch", 1),
            new ExpectedFact(FILE, 3, null, "jspecify_nullness_mismatch plus_other_stuff", 2),
            new ExpectedFact(FILE, 7, null, "test:cannot-convert:type1 to type2", 4),
            new ExpectedFact(FILE, 7, null, "test:expression-type:type2:expr2", 5),
            new ExpectedFact(FILE, 7, null, "test:sink-type:type1:expr1", 6),
            new ExpectedFact(FILE, 9, null, "test:irrelevant-annotation:@Nullable", 8))
        .inOrder();
    reader.checkErrors();
  }

  @Test
  public void readExpectedFacts_name() {
    assertThat(
            readExpectedFacts(
                "// test:name: test 1 ",
                "// jspecify_nullness_mismatch ",
                "// jspecify_nullness_mismatch plus_other_stuff ",
                "line under test",
                "// test:name: test 2 ",
                "// test:cannot-convert:type1 to type2 ",
                "// test:expression-type:type2:expr2 ",
                "// test:sink-type:type1:expr1 ",
                "another line under test",
                "// test:name: test 3 ",
                "// test:irrelevant-annotation:@Nullable ",
                "yet another line under test"))
        .containsExactly(
            new ExpectedFact(FILE, 4, "test 1", "jspecify_nullness_mismatch", 2),
            new ExpectedFact(FILE, 4, "test 1", "jspecify_nullness_mismatch plus_other_stuff", 3),
            new ExpectedFact(FILE, 9, "test 2", "test:cannot-convert:type1 to type2", 6),
            new ExpectedFact(FILE, 9, "test 2", "test:expression-type:type2:expr2", 7),
            new ExpectedFact(FILE, 9, "test 2", "test:sink-type:type1:expr1", 8),
            new ExpectedFact(FILE, 12, "test 3", "test:irrelevant-annotation:@Nullable", 11))
        .inOrder();
    reader.checkErrors();
  }

  @Test
  public void readExpectedFacts_name_lineNumber_throws() {
    ImmutableList<ExpectedFact> unused = readExpectedFacts("// test:name: 1234 ");
    assertThat(assertThrows(IllegalArgumentException.class, () -> reader.checkErrors()))
        .hasMessageThat()
        .contains("test name cannot be an integer or contain a colon");
  }

  @Test
  public void readExpectedFacts_name_colon_throws() {
    ImmutableList<ExpectedFact> unused = readExpectedFacts("// test:name:has a : colon");
    assertThat(assertThrows(IllegalArgumentException.class, () -> reader.checkErrors()))
        .hasMessageThat()
        .contains("test name cannot be an integer or contain a colon");
  }

  @Test
  public void readExpectedFacts_name_notFirst_throws() {
    ImmutableList<ExpectedFact> unused =
        readExpectedFacts(
            "// test:expression-type:type:expr", //
            "// test:name:testName",
            "line under test");
    assertThat(assertThrows(IllegalArgumentException.class, () -> reader.checkErrors()))
        .hasMessageThat()
        .contains("test name must come before assertions for a line");
  }

  @Test
  public void readExpectedFacts_name_noFact_throws() {
    ImmutableList<ExpectedFact> unused =
        readExpectedFacts(
            "// test:name:testName", //
            "line under test");
    assertThat(assertThrows(IllegalArgumentException.class, () -> reader.checkErrors()))
        .hasMessageThat()
        .contains("no expected facts");
  }

  @Test
  public void readExpectedFacts_name_second_name() {
    ImmutableList<ExpectedFact> unused =
        readExpectedFacts(
            "// test:name:testName1", //
            "// test:name:testName2");
    assertThat(assertThrows(IllegalArgumentException.class, () -> reader.checkErrors()))
        .hasMessageThat()
        .contains("test name already set");
  }

  @Test
  public void readExpectedFacts_name_notUnique() {
    ImmutableList<ExpectedFact> unused =
        readExpectedFacts(
            "// test:name:testName", //
            " // test:expression-type:type1:expr1",
            "line 1 under test",
            "// test:name:testName", //
            " // test:expression-type:type2:expr2",
            "line 2 under test");
    assertThat(assertThrows(IllegalArgumentException.class, () -> reader.checkErrors()))
        .hasMessageThat()
        .contains("test name not unique: test 'testName' appears on tests of lines [3, 6]");
  }
}
