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

import static java.util.Objects.requireNonNullElse;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/** A fact reported by the analysis under test. */
public abstract class ReportedFact extends Fact {

  protected ReportedFact(Path file, long lineNumber) {
    super(file, lineNumber);
  }

  @Override
  final String getFactText() {
    return requireNonNullElse(expectedFact(), toString());
  }

  /** Returns true if this reported fact must match an {@link ExpectedFact}. */
  protected abstract boolean mustBeExpected();

  /** Returns true if this reported fact matches the given expected fact. */
  protected boolean matches(ExpectedFact expectedFact) {
    return expectedFact.getFactText().equals(expectedFact());
  }

  /** Returns the equivalent expected fact. */
  protected abstract @Nullable String expectedFact();

  /** Returns the message reported, without the file name or line number. */
  @Override
  public abstract String toString();
}
