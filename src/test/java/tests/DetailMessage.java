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
  private static final Pattern DETAIL_MESSAGE_PATTERN =
      Pattern.compile(
          Joiner.on(" \\$\\$ ")
              .join(
                  "\\((?<messageKey>[^)]+)\\)", "(?<messagePartCount>\\d+)", "(?<messageParts>.*)"),
          DOTALL);

  private static final Pattern OFFSETS_PATTERN =
      Pattern.compile("(\\( (?<start>-?\\d+), (?<end>-?\\d+) \\))?");

  /** The message key for the user-visible text message that is emitted. */
  final String messageKey;

  /** The arguments to the text message format for the message key. */
  final ImmutableList<String> messageArguments;

  /** The offset within the file of the start of the code covered by the diagnostic. */
  final Integer offsetStart;

  /** The offset within the file of the end (exclusive) of the code covered by the diagnostic. */
  final Integer offsetEnd;

  /** The user-visible message emitted for the diagnostic. */
  final String readableMessage;

  /**
   * Returns an object parsed from a diagnostic message, or {@code null} if the message doesn't
   * match the expected format.
   *
   * @param rootDirectory if not null, a root directory prefix to remove from the file part of the
   *     message
   */
  static @Nullable DetailMessage parse(TestDiagnostic input, @Nullable Path rootDirectory) {
    Matcher detailsMatcher = DETAIL_MESSAGE_PATTERN.matcher(input.getMessage());
    if (!detailsMatcher.matches()) {
      // Return a message with no key or parts.
      return new DetailMessage(
          input.getFile(),
          input.getLineNumber(),
          input.getKind(),
          "",
          ImmutableList.of(),
          null,
          null,
          input.getMessage());
    }
    Path file = input.getFile();
    if (rootDirectory != null) {
      file = rootDirectory.relativize(file);
    }

    int messagePartCount = parseInt(detailsMatcher.group("messagePartCount"));
    List<String> messageParts =
        Splitter.on("$$")
            .trimResults()
            .limit(messagePartCount + 2)
            .splitToList(detailsMatcher.group("messageParts"));
    ImmutableList<String> messageArguments =
        ImmutableList.copyOf(Iterables.limit(messageParts, messagePartCount));
    String readableMessage = getLast(messageParts);

    Matcher offsetsMatcher = OFFSETS_PATTERN.matcher(messageParts.get(messagePartCount));
    checkArgument(offsetsMatcher.matches(), "unparseable offsets: %s", input);

    return new DetailMessage(
        file,
        input.getLineNumber(),
        input.getKind(),
        detailsMatcher.group("messageKey"),
        messageArguments,
        intOrNull(offsetsMatcher.group("start")),
        intOrNull(offsetsMatcher.group("end")),
        readableMessage);
  }

  private static Integer intOrNull(String input) {
    return input == null ? null : parseInt(input);
  }

  private DetailMessage(
      Path file,
      long lineNumber,
      DiagnosticKind diagnosticKind,
      String messageKey,
      ImmutableList<String> messageArguments,
      Integer offsetStart,
      Integer offsetEnd,
      String readableMessage) {
    super(file, lineNumber, diagnosticKind, readableMessage, false);
    this.messageKey = messageKey;
    this.messageArguments = messageArguments;
    this.offsetStart = offsetStart;
    this.offsetEnd = offsetEnd;
    this.readableMessage = readableMessage;
  }

  /**
   * True if this was parsed from an actual {@code -Adetailedmsgtext} message; false if this was
   * some other diagnostic.
   */
  boolean hasDetails() {
    return !messageKey.equals("");
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
    return super.equals(that)
        && messageKey.equals(that.messageKey)
        && messageArguments.equals(that.messageArguments)
        && Objects.equals(offsetStart, that.offsetStart)
        && Objects.equals(offsetEnd, that.offsetEnd)
        && readableMessage.equals(that.readableMessage);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        super.hashCode(), messageKey, messageArguments, offsetStart, offsetEnd, readableMessage);
  }

  @Override
  public String toString() {
    return String.format("%s:%d:%s: (%s) %s", file, lineNumber, kind, messageKey, readableMessage);
  }

  /** String format for debugging use. */
  String toDetailedString() {
    return toStringHelper(this)
        .add("file", file)
        .add("lineNumber", lineNumber)
        .add("kind", kind)
        .add("messageKey", messageKey)
        .add("messageArguments", messageArguments)
        .add("offsetStart", offsetStart)
        .add("offsetEnd", offsetEnd)
        .add("readableMessage", readableMessage)
        .toString();
  }
}
