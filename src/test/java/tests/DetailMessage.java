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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.checkerframework.framework.test.diagnostics.DiagnosticKind;
import org.checkerframework.framework.test.diagnostics.TestDiagnostic;
import org.jspecify.annotations.Nullable;

/**
 * Information about a reported diagnostic.
 *
 * <p>Checker Framework uses a special format to put parseable information about a diagnostic into
 * the message text. This object represents that information directly.
 */
final class DetailMessage extends TestDiagnostic {

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
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    DetailMessage that = (DetailMessage) o;
    return lineNumber == that.lineNumber
        && file.equals(that.file)
        && messageKey.equals(that.messageKey)
        && messageArguments.equals(that.messageArguments)
        && Objects.equals(offsetStart, that.offsetStart)
        && Objects.equals(offsetEnd, that.offsetEnd)
        && message.equals(that.message);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        file, lineNumber, messageKey, messageArguments, offsetStart, offsetEnd, message);
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
