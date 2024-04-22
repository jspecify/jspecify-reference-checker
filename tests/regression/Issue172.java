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

// Test case for Issue 172:
// https://github.com/jspecify/jspecify-reference-checker/issues/172

import org.jspecify.annotations.NullMarked;

class Issue172<E> {
  E e() {
    return null;
  }
}

class Issue172UnmarkedUse {
  void foo(Issue172<Object> p) {}
}

@NullMarked
class Issue172MarkedUse {
  void foo(Issue172<Object> p) {}
}
