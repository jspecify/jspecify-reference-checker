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

import javax.lang.model.element.ExecutableElement;

final class Util {
  /*
   * NOTE: This DOES NOT match methods that OVERRIDE the given method. For example,
   * `nameMatches(method, "Map", "get")` will NOT match a call that is statically resolved to
   * `HashMap.get`.
   *
   * This is actually mildly convenient for least one existing caller: It ensures that we're looking
   * at plain Stream.filter and not some hypothetical override. That means that we know that the
   * return type is Stream<...>, rather than a subtype with more than one type parameters, with zero
   * type parameters, or one type parameter whose "purpose" doesn't match that of Stream's own type
   * parameter.
   *
   * Still, this might not be the behavior we desire in other cases.
   */
  static boolean nameMatches(ExecutableElement executable, String clazz, String method) {
    return executable.getSimpleName().contentEquals(method)
        && executable.getEnclosingElement().getSimpleName().contentEquals(clazz);
  }

  private Util() {}
}
