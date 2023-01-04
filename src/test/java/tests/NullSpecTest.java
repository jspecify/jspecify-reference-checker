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

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getLast;
import static java.lang.Integer.parseInt;
import static java.util.regex.Pattern.DOTALL;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.jspecify.nullness.NullSpecChecker;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.checkerframework.framework.test.CheckerFrameworkPerDirectoryTest;
import org.checkerframework.framework.test.TypecheckResult;
import org.checkerframework.framework.test.diagnostics.DiagnosticKind;
import org.checkerframework.framework.test.diagnostics.TestDiagnostic;
import org.checkerframework.javacutil.BugInCF;
import org.jspecify.annotations.Nullable;
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
    return options.build().toArray(String[]::new);
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
      DetailMessage detailMessage = DetailMessage.parse(diagnostic.getMessage());
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
        || missing.getMessage().contains("jspecify_nullness_mismatch")) {
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

  /**
   * Information about a reported diagnostic.
   *
   * <p>Checker Framework uses a special format to put parseable information about a diagnostic into
   * the message text. This object represents that information directly.
   */
  private static final class DetailMessage extends TestDiagnostic {
    /** Parser for the output for -Adetailedmsgtext. */
    // Implemented here: org.checkerframework.framework.source.SourceChecker#detailedMsgTextPrefix
    static final Pattern DETAIL_MESSAGE_PATTERN =
        Pattern.compile(
            Joiner.on(" \\$\\$ ")
                .join(
                    "(?<file>\\S+):(?<lineNumber>\\d+): error: \\((?<messageKey>[^)]+)\\)",
                    "(?<messagePartCount>\\d+)",
                    "(?<messageParts>.*)"),
            DOTALL);

    static final Pattern OFFSETS_PATTERN =
        Pattern.compile("(\\( (?<start>-?\\d+), (?<end>-?\\d+) \\))?");

    /** The path to the source file containing the diagnostic. */
    final Path file;

    /** The line number (1-based) of the diagnostic in the {@link #file}. */
    final int lineNumber;

    /** The message key for the user-visible text message that is emitted. */
    final String messageKey;

    /** The arguments to the text message format for the message key. */
    final ImmutableList<String> messageArguments;

    /** The offset within the file of the start of the code covered by the diagnostic. */
    final Integer offsetStart;

    /** The offset within the file of the end (exclusive) of the code covered by the diagnostic. */
    final Integer offsetEnd;

    /** The user-visible message emitted for the diagnostic. */
    final String message;

    /**
     * Returns an object parsed from a diagnostic message, or {@code null} if the message doesn't
     * match the expected format.
     */
    static @Nullable DetailMessage parse(String input) {
      Matcher matcher = DETAIL_MESSAGE_PATTERN.matcher(input);
      if (!matcher.matches()) {
        return null;
      }

      int messagePartCount = parseInt(matcher.group("messagePartCount"));
      List<String> messageParts =
          Splitter.on("$$")
              .trimResults()
              .limit(messagePartCount + 2)
              .splitToList(matcher.group("messageParts"));
      ImmutableList<String> messageArguments =
          ImmutableList.copyOf(Iterables.limit(messageParts, messagePartCount));
      String message = getLast(messageParts);

      Matcher offsets = OFFSETS_PATTERN.matcher(messageParts.get(messagePartCount));
      checkArgument(offsets.matches(), "unparseable offsets: %s", input);

      return new DetailMessage(
          Paths.get(matcher.group("file")),
          parseInt(matcher.group("lineNumber")),
          matcher.group("messageKey"),
          messageArguments,
          intOrNull(offsets.group("start")),
          intOrNull(offsets.group("end")),
          message);
    }

    static Integer intOrNull(String input) {
      return input == null ? null : parseInt(input);
    }

    DetailMessage(
        Path file,
        int lineNumber,
        String messageKey,
        ImmutableList<String> messageArguments,
        Integer offsetStart,
        Integer offsetEnd,
        String message) {
      super(file.toString(), lineNumber, DiagnosticKind.Error, message, false, true);
      this.file = file;
      this.lineNumber = lineNumber;
      this.messageKey = messageKey;
      this.messageArguments = messageArguments;
      this.offsetStart = offsetStart;
      this.offsetEnd = offsetEnd;
      this.message = message;
    }

    /** The last part of the {@link #file}. */
    String getFileName() {
      return file.getFileName().toString();
    }

    @Override
    public String toString() {
      return String.format("%s:%d: (%s) %s", file, lineNumber, messageKey, message);
    }

    /** String format for debugging use. */
    String toDetailedString() {
      return toStringHelper(this)
          .add("file", file)
          .add("lineNumber", lineNumber)
          .add("messageKey", messageKey)
          .add("messageArguments", messageArguments)
          .add("offsetStart", offsetStart)
          .add("offsetEnd", offsetEnd)
          .add("message", message)
          .toString();
    }
  }
}
