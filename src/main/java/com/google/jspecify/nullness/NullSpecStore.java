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

import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.framework.flow.CFAbstractStore;
import org.checkerframework.framework.flow.CFValue;

final class NullSpecStore extends CFAbstractStore<CFValue, NullSpecStore> {
  NullSpecStore(NullSpecAnalysis analysis, boolean sequentialSemantics) {
    super(analysis, sequentialSemantics);
  }

  NullSpecStore(NullSpecStore other) {
    super(other);
  }

  @Override
  protected boolean shouldInsert(
      JavaExpression expr, CFValue value, boolean permitNondeterministic) {
    return super.shouldInsert(expr, value, /* permitNondeterministic= */ true);
  }
}
