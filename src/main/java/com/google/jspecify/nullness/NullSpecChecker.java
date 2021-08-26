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

import java.util.SortedSet;
import java.util.TreeSet;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.framework.source.SupportedOptions;

/**
 * Main entry point for a jspecify nullness checker.
 *
 * <p>Supported options:
 *
 * <ol>
 *   <li>"strict": Whether the checker should be a sound, strict type system. Does not imply that
 *       implementation code is checked.
 *   <li>"checkImpl": Whether implementation code should be checked.
 *   <li>"aliasCFannos": Whether Checker Framework Nullness annotations should be aliases - required
 *       to use the Checker Framework JDK specifications.
 * </ol>
 */
@SupportedOptions({"strict", "checkImpl", "aliasCFannos"}) // TODO(cpovirk): Remove aliasCFannos.
public final class NullSpecChecker extends BaseTypeChecker {
  /*
   * A non-final field is ugly, but we can't create our Util instance in the constructor because the
   * ProcessingEnvironment isn't available then.
   *
   * But why make it a field at all, as opposed to a local variable in createSourceVisitor? The
   * problem is that NullSpecVisitor's constructor needs a way to access it through the checker
   * object. It needs that because the BaseTypeVisitor constructor (NullSpecVisitor's
   * superconstructor) calls createTypeFactory, and createTypeFactory needs access to util -- before
   * the NullSpecVisitor constructor has run. Thus, to access it, it has to be able to read it
   * through the BaseTypeVisitor.checker field, which *has* already been initialized. That means
   * reading it through this field.
   */
  Util util;

  public NullSpecChecker() {}

  @Override
  public SortedSet<String> getSuppressWarningsPrefixes() {
    SortedSet<String> prefixes = new TreeSet<>();
    prefixes.add("nullness");
    return prefixes;
  }

  @Override
  protected BaseTypeVisitor<?> createSourceVisitor() {
    this.util = new Util(getElementUtils(), getTypeUtils()); // see discussion on the field
    return new NullSpecVisitor(this, util);
  }
}
