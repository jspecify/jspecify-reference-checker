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

// Test case for Issue 163:
// https://github.com/jspecify/jspecify-reference-checker/issues/163

import org.jspecify.annotations.NullMarked;

@NullMarked
class Issue163NullForUnspecVoid {
  void x(Issue163Value val, Issue163Visitor<Void> vis) {
    val.accept(vis, null);
  }
}

interface Issue163Value {
  <P> void accept(Issue163Visitor<P> visitor, P param);
}

interface Issue163Visitor<P> {}
