// Copyright 2022 The JSpecify Authors
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNullElse;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.jspecify.nullness.NullSpecChecker;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.checkerframework.framework.test.TestConfiguration;
import org.checkerframework.framework.test.TestConfigurationBuilder;
import org.checkerframework.framework.test.TestUtilities;
import org.checkerframework.framework.test.TypecheckExecutor;
import org.checkerframework.framework.test.TypecheckResult;
import org.checkerframework.framework.test.diagnostics.DiagnosticKind;
import org.jspecify.annotations.Nullable;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import tests.conformance.AbstractConformanceTest;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.CannotConvert;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.ExpectedFact;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.IrrelevantAnnotation;

/** An {@link AbstractConformanceTest} for the JSpecify reference checker. */
@RunWith(JUnit4.class)
public final class ConformanceTest extends AbstractConformanceTest {

  private static final ImmutableList<String> OPTIONS =
      ImmutableList.of(
          "-AassumePure",
          "-Adetailedmsgtext",
          "-AcheckImpl",
          "-AsuppressWarnings=conditional",
          "-Astrict",
          "-AajavaChecks");

  @Override
  protected Iterable<ReportedFact> analyze(ImmutableList<Path> files) {
    TestConfiguration config =
        TestConfigurationBuilder.buildDefaultConfiguration(
            null,
            files.stream().map(Path::toFile).collect(toImmutableSet()),
            emptyList(),
            ImmutableList.of(NullSpecChecker.class.getName()),
            OPTIONS,
            TestUtilities.getShouldEmitDebugInfo());
    TypecheckResult result = new TypecheckExecutor().runTest(config);
    return result.getUnexpectedDiagnostics().stream()
        .map(d -> DetailMessage.parse(d.getMessage(), getTestDirectory()))
        .filter(Objects::nonNull)
        .map(DetailMessageReportedFact::new)
        .collect(toImmutableSet());
  }

  /** A {@link ReportedFact} parsed from a Checker Framework {@link DetailMessage}. */
  static final class DetailMessageReportedFact extends ReportedFact {

    private static final ImmutableSet<String> NULLNESS_MISMATCH_KEYS =
        ImmutableSet.of(
            "argument",
            "assignment",
            "atomicreference.must.include.null",
            "cast.unsafe",
            "dereference",
            "lambda.param",
            "methodref.receiver.bound",
            "methodref.receiver",
            "methodref.return",
            "override.param",
            "override.return",
            "return",
            "threadlocal.must.include.null",
            "type.argument");

    private static final ImmutableSet<String> CANNOT_CONVERT_KEYS =
        ImmutableSet.of(
            "argument",
            "assignment",
            "atomicreference.must.include.null",
            "cast.unsafe",
            "lambda.param",
            "methodref.receiver.bound",
            "methodref.receiver",
            "methodref.return",
            "override.param",
            "override.return",
            "return",
            "threadlocal.must.include.null",
            "type.argument");

    private static final ImmutableSet<String> IRRELEVANT_ANNOTATION_KEYS =
        ImmutableSet.of("primitive.annotated", "type.parameter.annotated");

    private final DetailMessage detailMessage;

    DetailMessageReportedFact(DetailMessage detailMessage) {
      super(detailMessage.file, detailMessage.lineNumber);
      this.detailMessage = detailMessage;
    }

    @Override
    protected boolean matches(ExpectedFact expectedFact) {
      switch (expectedFact.kind()) {
        case NULLNESS_MISMATCH:
          return NULLNESS_MISMATCH_KEYS.contains(detailMessage.messageKey);

        default:
          return super.matches(expectedFact);
      }
    }

    @Override
    protected boolean mustBeExpected() {
      return detailMessage.getKind().equals(DiagnosticKind.Error);
    }

    @Override
    protected @Nullable ExpectedFact expectedFact() {
      if (CANNOT_CONVERT_KEYS.contains(detailMessage.messageKey)) {
        ImmutableList<String> reversedArguments = detailMessage.messageArguments.reverse();
        String sourceType = fixType(reversedArguments.get(1)); // penultimate
        String sinkType = fixType(reversedArguments.get(0)); // last
        return CannotConvert.create(sourceType, sinkType);
      }
      if (IRRELEVANT_ANNOTATION_KEYS.contains(detailMessage.messageKey)) {
        return IrrelevantAnnotation.create("Nullable"); // TODO(dpb): Support other annotations.
      }
      return null;
    }

    @Override
    public String toString() {
      return String.format("(%s) %s", detailMessage.messageKey, detailMessage.message);
    }

    /**
     * Rewrite the CF types into JSpecify types.
     *
     * <ul>
     *   <li>Nullness sigils {@code ?}, {@code !}, and {@code *} move from after the type arguments
     *       to before them.
     *   <li>If there is no nullness sigil, use {@code !}. (TODO: What about parametric nullness?)
     * </ul>
     */
    private static String fixType(String input) {
      Matcher capture = CAPTURE_TYPE.matcher(input);
      if (capture.matches()) {
        String bound = fixType(capture.group("bound"));
        String suffix = requireNonNullElse(capture.group("suffix"), "!");
        return String.format("{capture of ? extends %s}%s", bound, suffix);
      }
      Matcher type = TYPE.matcher(input);
      checkArgument(type.matches(), "did not match for \"%s\"", input);
      String args = type.group("args");
      String suffix = type.group("suffix");
      if (args == null && suffix != null) {
        return input;
      }
      StringBuilder newType = new StringBuilder(type.group("raw"));
      newType.append(requireNonNullElse(suffix, "!"));
      if (args != null) {
        newType.append(
            COMMA_SPLITTER
                .splitToStream(args)
                .map(DetailMessageReportedFact::fixType)
                .collect(joining(",", "<", ">")));
      }
      return newType.toString();
    }

    private static final Pattern TYPE =
        Pattern.compile("(?<raw>[^<,?!*]+)(?:<(?<args>.+)>)?(?<suffix>[?!*])?");

    private static final Pattern CAPTURE_TYPE =
        Pattern.compile("\\{capture#\\d+ of \\? extends (?<bound>.*)}(?<suffix>[?!*])?");

    private static final Splitter COMMA_SPLITTER = Splitter.on(",");
  }
}
