// Copyright 2021 The JSpecify Authors
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

import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.flow.CFAbstractAnalysis;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.javacutil.Pair;

final class NullSpecAnalysis extends CFAbstractAnalysis<CFValue, NullSpecStore, NullSpecTransfer> {
  NullSpecAnalysis(
      BaseTypeChecker checker,
      NullSpecAnnotatedTypeFactory factory,
      List<Pair<VariableElement, CFValue>> fieldValues) {
    super(checker, factory, fieldValues);
  }

  @Override
  public NullSpecStore createEmptyStore(boolean sequentialSemantics) {
    return new NullSpecStore(this, sequentialSemantics);
  }

  @Override
  public NullSpecStore createCopiedStore(NullSpecStore other) {
    return new NullSpecStore(other);
  }

  @Override
  public CFValue createAbstractValue(Set<AnnotationMirror> annotations, TypeMirror underlyingType) {
    return defaultCreateAbstractValue(this, annotations, underlyingType);
  }
}
