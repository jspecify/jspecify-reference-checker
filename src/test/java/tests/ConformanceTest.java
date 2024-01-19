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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.stream.Collectors.joining;
import static org.jspecify.conformance.ConformanceTestRunner.ExpectedFact.cannotConvert;
import static org.jspecify.conformance.ConformanceTestRunner.ExpectedFact.expressionType;
import static org.jspecify.conformance.ConformanceTestRunner.ExpectedFact.irrelevantAnnotation;
import static org.jspecify.conformance.ConformanceTestRunner.ExpectedFact.isNullnessMismatch;
import static org.jspecify.conformance.ConformanceTestRunner.ExpectedFact.sinkType;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.jspecify.nullness.NullSpecChecker;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.checkerframework.framework.test.TestConfiguration;
import org.checkerframework.framework.test.TestConfigurationBuilder;
import org.checkerframework.framework.test.TestUtilities;
import org.checkerframework.framework.test.TypecheckExecutor;
import org.checkerframework.framework.test.TypecheckResult;
import org.checkerframework.framework.test.diagnostics.DiagnosticKind;
import org.jspecify.annotations.Nullable;
import org.jspecify.conformance.ConformanceTestRunner;
import org.jspecify.conformance.ConformanceTestRunner.ReportedFact;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Conformance tests for the JSpecify reference checker.
 *
 * <p>To configure:
 *
 * <ul>
 *   <li>Set the system property {@code JSpecifyConformanceTest.tests} to the location of the
 *       JSpecify conformance test sources.
 *   <li>Set the system property {@code JSpecifyConformanceTest.deps} to a colon-separated list of
 *       JARs that must be on the classpath when analyzing the JSpecify conformance test sources.
 *   <li>Set the sytem property {@code JSpecifyConformanceTest.report} to the location of the stored
 *       test report.
 *   <li>Do the same, but for {@code JSpecifyConformanceTest.samples.tests} and {@code
 *       JSpecifyConformanceTest.samples.report}, for running the conformance tests on the JSpecify
 *       samples directory.
 * </ul>
 *
 * <p>The test can run in one of three modes, depending on the value of the {@code
 * JSPECIFY_CONFORMANCE_TEST_MODE} environment variable:
 *
 * <dl>
 *   <dt>{@code compare} or empty
 *   <dd>Compare the analysis to the stored report, and fail if anything has changed. Note that
 *       failure isn't always bad! If an assertion used to fail and now passes, this test will
 *       "fail".
 *   <dt>{@code write}
 *   <dd>Write the analysis to the report file. Always passes.
 *   <dt>{@code details}
 *   <dd>Fail if any assertion fails. Report details of unexpected facts.
 * </dl>
 */
@RunWith(JUnit4.class)
public final class ConformanceTest {
  private static final ImmutableList<String> OPTIONS =
      ImmutableList.of(
          "-AassumePure",
          "-Adetailedmsgtext",
          "-AcheckImpl",
          "-AsuppressWarnings=conditional",
          "-Astrict",
          "-AshowTypes");

  private static final ImmutableList<Path> TEST_DEPS =
      Stream.ofNullable(System.getProperty("JSpecifyConformanceTest.deps"))
          .flatMap(Splitter.on(':').trimResults().omitEmptyStrings()::splitToStream)
          .map(Paths::get)
          .collect(toImmutableList());

  private final ConformanceTestRunner conformanceTestRunner =
      new ConformanceTestRunner(ConformanceTest::analyze);

  @Test
  public void conformanceTests() throws IOException {
    conformanceTestRunner.checkConformance(testDirectory(null), TEST_DEPS, testReport(null));
  }

  @Test
  public void conformanceTestsOnSamples() throws IOException {
    conformanceTestRunner.checkConformance(
        testDirectory("samples"), ImmutableList.of(), testReport("samples"));
  }

  private static Path testDirectory(@Nullable String prefix) {
    return systemPropertyPath(
        PROPERTY_JOINER.join("JSpecifyConformanceTest", prefix, "inputs"),
        "the location of the JSpecify conformance test inputs");
  }

  private static Path testReport(@Nullable String prefix) {
    return systemPropertyPath(
        PROPERTY_JOINER.join("JSpecifyConformanceTest", prefix, "report"),
        "the location of the JSpecify conformance test report");
  }

  private static final Joiner PROPERTY_JOINER = Joiner.on('.').skipNulls();

  private static Path systemPropertyPath(String key, String description) {
    return Paths.get(
        requireNonNull(
            System.getProperty(key),
            String.format("Set system property %s to %s.", key, description)));
  }

  private static ImmutableSet<ReportedFact> analyze(
      Path testDirectory, ImmutableList<Path> files, ImmutableList<Path> testDeps) {
    TestConfiguration config =
        TestConfigurationBuilder.buildDefaultConfiguration(
            null,
            files.stream().map(Path::toFile).collect(toImmutableSet()),
            testDeps.stream().map(Path::toString).collect(toImmutableList()),
            ImmutableList.of(NullSpecChecker.class.getName()),
            OPTIONS,
            TestUtilities.getShouldEmitDebugInfo());
    TypecheckResult result = new TypecheckExecutor().runTest(config);
    return result.getUnexpectedDiagnostics().stream()
        .map(d -> DetailMessage.parse(d.getMessage(), testDirectory))
        .filter(Objects::nonNull)
        .map(DetailMessageReportedFact::new)
        .collect(toImmutableSet());
  }

  /** A {@link ReportedFact} parsed from a Checker Framework {@link DetailMessage}. */
  static final class DetailMessageReportedFact extends ReportedFact {

    private static final String DEREFERENCE = "dereference";

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
    protected boolean matches(String expectedFact) {
      if (isNullnessMismatch(expectedFact)) {
        return DEREFERENCE.equals(detailMessage.messageKey)
            || CANNOT_CONVERT_KEYS.contains(detailMessage.messageKey);
      }
      return super.matches(expectedFact);
    }

    @Override
    protected boolean mustBeExpected() {
      return detailMessage.getKind().equals(DiagnosticKind.Error);
    }

    @Override
    protected @Nullable String expectedFact() {
      if (CANNOT_CONVERT_KEYS.contains(detailMessage.messageKey)) {
        if (detailMessage.messageArguments.size() < 2) {
          return null; // The arguments must end with sourceType and sinkType.
        }
        ImmutableList<String> reversedArguments = detailMessage.messageArguments.reverse();
        String sourceType = fixType(reversedArguments.get(1)); // penultimate
        String sinkType = fixType(reversedArguments.get(0)); // last
        return cannotConvert(sourceType, sinkType);
      }
      if (IRRELEVANT_ANNOTATION_KEYS.contains(detailMessage.messageKey)) {
        return irrelevantAnnotation("Nullable"); // TODO(dpb): Support other annotations.
      }
      switch (detailMessage.messageKey) {
        case "sourceType":
          {
            String expressionType = fixType(detailMessage.messageArguments.get(0));
            String expression = detailMessage.messageArguments.get(1);
            return expressionType(expressionType, expression);
          }
        case "sinkType":
          {
            String sinkType = fixType(detailMessage.messageArguments.get(0));
            // Remove the simple name of the class and the dot before the method name.
            String sink = detailMessage.messageArguments.get(1).replaceFirst("^[^.]+\\.", "");
            return sinkType(sinkType, sink);
          }
      }
      return null;
    }

    @Override
    public String toString() {
      return String.format("(%s) %s", detailMessage.messageKey, detailMessage.readableMessage);
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
    private static String fixType(String type) {
      Matcher matcher = TYPE.matcher(type);
      if (!matcher.matches()) {
        return type;
      }
      String args = matcher.group("args");
      String suffix = matcher.group("suffix");
      if (args == null && suffix != null) {
        return type;
      }
      StringBuilder newType = new StringBuilder(matcher.group("raw"));
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

    private static final Splitter COMMA_SPLITTER = Splitter.on(",");
  }
}
