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

package com.google.jspecify.nullness;

import javax.lang.model.element.AnnotationMirror;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeValidator;
import org.checkerframework.framework.type.AnnotatedTypeMirror;

final class NullSpecTypeValidator extends BaseTypeValidator {
  private final AnnotationMirror nullnessOperatorUnspecified;

  /** Constructor. */
  NullSpecTypeValidator(
      BaseTypeChecker checker,
      NullSpecVisitor visitor,
      NullSpecAnnotatedTypeFactory atypeFactory,
      Util util) {
    super(checker, visitor, atypeFactory);

    nullnessOperatorUnspecified = util.nullnessOperatorUnspecified;
  }

  @Override
  public boolean areBoundsValid(AnnotatedTypeMirror upperBound, AnnotatedTypeMirror lowerBound) {
    if (upperBound.hasAnnotation(nullnessOperatorUnspecified)
        || lowerBound.hasAnnotation(nullnessOperatorUnspecified)) {
      return true;
    } else {
      return super.areBoundsValid(upperBound, lowerBound);
    }
  }
}
