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

// Test case based on comment:
// https://github.com/jspecify/jspecify-reference-checker/pull/165#issuecomment-2030038854

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
class OverrideTest<A extends @Nullable Object> {
  abstract class Super {
    abstract void accept(MarkedEntry<A> entry);
  }

  abstract class Sub extends Super {
    @Override
    abstract void accept(MarkedEntry<A> entry);
  }

  interface MarkedEntry<B extends @Nullable Object> {}

  abstract class SuperUnmarked {
    abstract void accept(OverrideTestUnmarkedEntry<A> entry);
  }

  abstract class SubUnmarked extends SuperUnmarked {
    @Override
    abstract void accept(OverrideTestUnmarkedEntry<A> entry);
  }
}

interface OverrideTestUnmarkedEntry<C extends @Nullable Object> {}
