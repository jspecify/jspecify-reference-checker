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

// Test case for Issue 159:
// https://github.com/jspecify/jspecify-reference-checker/issues/159

import java.util.ArrayList;
import org.jspecify.annotations.NullMarked;

@NullMarked
class Issue159<E> extends ArrayList<E> {
  <F> Issue159<F> foo() {
    return new Issue159<F>();
  }
}
