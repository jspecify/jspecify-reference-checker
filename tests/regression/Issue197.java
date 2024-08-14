// Copyright 2024 The JSpecify Authors
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

// Test case for Issue 197:
// https://github.com/jspecify/jspecify-reference-checker/issues/197

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

class Issue197<E> {
  interface Function<A, B> {}

  interface Super<E> {
    void i(Function<? super E, ? extends E> p);
  }

  @NullMarked
  interface Sub<E extends @Nullable Object> extends Super<E> {
    @Override
    void i(Function<? super E, ? extends E> p);
  }
}
