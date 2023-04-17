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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Collections.emptyList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.jspecify.nullness.NullSpecChecker;
import java.nio.file.Path;
import java.util.Objects;
import org.checkerframework.framework.test.TestConfiguration;
import org.checkerframework.framework.test.TestConfigurationBuilder;
import org.checkerframework.framework.test.TestUtilities;
import org.checkerframework.framework.test.TypecheckExecutor;
import org.checkerframework.framework.test.TypecheckResult;
import org.checkerframework.framework.test.diagnostics.DiagnosticKind;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import tests.conformance.AbstractConformanceTest;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.ExpectedFactAssertion.CannotConvert;
import tests.conformance.AbstractConformanceTest.ConformanceTestAssertion.ExpectedFactAssertion.NullnessMismatch;

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

    private final DetailMessage detailMessage;

    DetailMessageReportedFact(DetailMessage detailMessage) {
      super(detailMessage.file, detailMessage.lineNumber);
      this.detailMessage = detailMessage;
    }

    @Override
    protected boolean matches(NullnessMismatch nullnessMismatch) {
      return NULLNESS_MISMATCH_KEYS.contains(detailMessage.messageKey);
    }

    @Override
    protected boolean matches(CannotConvert cannotConvert) {
      return NULLNESS_MISMATCH_KEYS.contains(detailMessage.messageKey);
    }

    @Override
    protected boolean mustBeExpected() {
      return detailMessage.getKind().equals(DiagnosticKind.Error);
    }

    @Override
    public String toString() {
      return String.format("(%s) %s", detailMessage.messageKey, detailMessage.message);
    }
  }
}
