// Copyright 2020 The JSpecify Authors
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

package tests;

import com.google.common.collect.ImmutableList;
import com.google.jspecify.nullness.NullSpecChecker;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import org.checkerframework.framework.test.CheckerFrameworkPerDirectoryTest;
import org.checkerframework.framework.test.TypecheckResult;
import org.checkerframework.framework.test.diagnostics.DiagnosticKind;
import org.checkerframework.framework.test.diagnostics.TestDiagnostic;
import org.checkerframework.javacutil.BugInCF;
import org.junit.runners.Parameterized.Parameters;

/** Tests for the {@link NullSpecChecker} that look at expected diagnostics in source files. */
abstract class NullSpecTest extends CheckerFrameworkPerDirectoryTest {
  /** A minimal smoke test that looks at one simple file. */
  public static class Minimal extends NullSpecTest {
    public Minimal(List<File> testFiles) {
      super(testFiles, false);
    }

    @Parameters
    public static String[] getTestDirs() {
      return new String[] {"minimal"};
    }
  }

  /** A test that ignores cases where there is limited nullness information. */
  public static class Lenient extends NullSpecTest {
    public Lenient(List<File> testFiles) {
      super(testFiles, false);
    }

    @Parameters
    public static String[] getTestDirs() {
      return new String[] {"../../jspecify/samples"};
    }
  }

  /** A test that examines cases where there is limited nullness information. */
  public static class Strict extends NullSpecTest {
    public Strict(List<File> testFiles) {
      super(testFiles, true);
    }

    @Parameters
    public static String[] getTestDirs() {
      return new String[] {"../../jspecify/samples"};
    }
  }

  private final boolean strict;

  NullSpecTest(List<File> testFiles, boolean strict) {
    super(testFiles, NullSpecChecker.class, "NullSpec", checkerOptions(strict));
    this.strict = strict;
  }

  private static String[] checkerOptions(boolean strict) {
    ImmutableList.Builder<String> options = ImmutableList.builder();
    options.add(
        "-AassumePure", "-Adetailedmsgtext", "-AcheckImpl", "-AsuppressWarnings=conditional");
    if (strict) {
      options.add("-Astrict");
    }
    return options.build().toArray(new String[0]);
  }

  @Override
  public TypecheckResult adjustTypecheckResult(TypecheckResult testResult) {
    // Aliases to lists in testResult - side-effecting the returned value.
    List<TestDiagnostic> missing = testResult.getMissingDiagnostics();

    missing.removeIf(m -> m.getMessage().contains("jspecify_but_expect_nothing"));
    if (!strict) {
      // Filter out all errors due to limited information
      missing.removeIf(m -> m.getMessage().contains("jspecify_but_expect_warning"));
      missing.removeIf(
          m ->
              m.getMessage().contains("jspecify_nullness_not_enough_information")
                  && !m.getMessage().contains("jspecify_but_expect_error"));
    }

    List<TestDiagnostic> unexpected = testResult.getUnexpectedDiagnostics();

    for (ListIterator<TestDiagnostic> i = unexpected.listIterator(); i.hasNext(); ) {
      TestDiagnostic diagnostic = i.next();
      DetailMessage detailMessage = DetailMessage.parse(diagnostic.getMessage(), null);
      if (detailMessage != null) {
        // Replace diagnostics that can be parsed with DetailMessage diagnostics.
        i.set(detailMessage);
      } else if (diagnostic.getKind() != DiagnosticKind.Error) {
        // Remove warnings like explicit.annotation.ignored and deprecation.
        i.remove();
      }
    }

    // The original values to allow multiple complete iterations - multiple unexpected errors
    // can be mapped to a single missing error.
    List<TestDiagnostic> origMissing = new ArrayList<>(missing);
    List<TestDiagnostic> origUnexpected = new ArrayList<>(unexpected);

    for (TestDiagnostic m : origMissing) {
      unexpected.removeIf(u -> corresponds(m, u));
    }
    for (TestDiagnostic u : origUnexpected) {
      missing.removeIf(m -> corresponds(m, u));
    }

    return testResult;
  }

  /**
   * Returns {@code true} if {@code missing} is a JSpecify directive that matches {@code
   * unexpected}, a reported diagnostic.
   */
  private boolean corresponds(TestDiagnostic missing, TestDiagnostic unexpected) {
    return unexpected instanceof DetailMessage
        && corresponds(missing, ((DetailMessage) unexpected));
  }

  /**
   * Returns {@code true} if {@code missing} is a JSpecify directive that matches {@code
   * unexpected}, a reported diagnostic.
   */
  private boolean corresponds(TestDiagnostic missing, DetailMessage unexpected) {
    // First, make sure the two diagnostics are on the same file and line.
    if (!missing.getFilename().equals(unexpected.getFileName())
        || missing.getLineNumber() != unexpected.lineNumber) {
      return false;
    }

    if (missing.getMessage().contains("jspecify_but_expect_error")
        || missing.getMessage().contains("jspecify_but_expect_warning")
        || missing.getMessage().contains("jspecify_nullness_not_enough_information")
        || missing.getMessage().contains("jspecify_nullness_mismatch")
        || missing.getMessage().contains("test:cannot-convert")) {
      switch (unexpected.messageKey) {
        case "argument":
        case "assignment":
        case "atomicreference.must.include.null":
        case "cast.unsafe":
        case "dereference":
        case "lambda.param":
        case "methodref.receiver.bound":
        case "methodref.receiver":
        case "methodref.return":
        case "override.param":
        case "override.return":
        case "return":
        case "threadlocal.must.include.null":
        case "type.argument":
          return true;
        default:
          return false;
      }
    }

    switch (missing.getMessage()) {
      case "jspecify_nullness_intrinsically_not_nullable":
        switch (unexpected.messageKey) {
          case "enum.constant.annotated":
          case "outer.annotated":
          case "primitive.annotated":
            return true;
          default:
            return false;
        }
      case "jspecify_unrecognized_location":
        switch (unexpected.messageKey) {
            /*
             * We'd rather avoid this `bound` error (in part because it suggests that the annotation
             * is having some effect, which we don't want!), but the most important thing is that the
             * checker is issuing one or more errors when someone annotates a type-parameter
             * declaration. The second most important thing is that the errors issued include our
             * custom `*.annotated` error. This test probably doesn't confirm that second thing
             * anymore, but I did manually confirm that it is true as of this writing.
             */
          case "bound":
          case "local.variable.annotated":
          case "type.parameter.annotated":
          case "wildcard.annotated":
            return true;
          default:
            return false;
        }
      case "jspecify_conflicting_annotations":
        switch (unexpected.messageKey) {
          case "conflicting.annos":
            return true;
          default:
            return false;
        }
      default:
        throw new BugInCF("Unexpected missing diagnostic: " + missing.getMessage());
    }
  }
}
