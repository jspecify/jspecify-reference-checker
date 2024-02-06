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

import java.nio.file.Path;

/** A fact reported by the analysis under test. */
public abstract class ReportedFact extends Fact {

  protected ReportedFact(Path file, long lineNumber) {
    super(file, lineNumber);
  }

  /** Returns true if this reported fact matches the given expected fact. */
  protected boolean matches(ExpectedFact expectedFact) {
    return expectedFact.getFactText().equals(getFactText());
  }

  /** Returns true if this reported fact must match an {@link ExpectedFact}. */
  protected abstract boolean mustBeExpected();

  /**
   * Returns {@linkplain Fact#getFactText() fact text} representing that the source type cannot be
   * converted to the sink type in any world.
   */
  protected static String cannotConvert(String sourceType, String sinkType) {
    return String.format("test:cannot-convert:%s to %s", sourceType, sinkType);
  }

  /** Returns {@linkplain Fact#getFactText() fact text} representing an expected expression type. */
  protected static String expressionType(String expressionType, String expression) {
    return String.format("test:expression-type:%s:%s", expressionType, expression);
  }

  /**
   * Returns {@linkplain Fact#getFactText() fact text} representing that an annotation is not
   * relevant.
   */
  protected static String irrelevantAnnotation(String annotationType) {
    return String.format("test:irrelevant-annotation:%s", annotationType);
  }

  /**
   * Returns {@linkplain Fact#getFactText() fact text} representing that an annotation is not
   * relevant.
   */
  protected static String sinkType(String sinkType, String sink) {
    return String.format("test:sink-type:%s:%s", sinkType, sink);
  }
}
