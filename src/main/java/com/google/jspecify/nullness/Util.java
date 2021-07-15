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

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toList;
import static org.checkerframework.javacutil.TreeUtils.annotationsFromTypeAnnotationTrees;
import static org.checkerframework.javacutil.TreeUtils.elementFromUse;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleAnnotationValueVisitor8;
import javax.lang.model.util.Types;
import org.checkerframework.framework.qual.TypeUseLocation;

final class Util {
  // TODO(cpovirk): Make Util instantiable so it can hold `Elements`, `TypeMirror` objects, etc.?

  static Optional<TypeElement> optionalTypeElement(Elements e, String name) {
    return Optional.ofNullable(e.getTypeElement(name));
  }

  static boolean isOrOverrides(
      Elements elementUtils, ExecutableElement overrider, ExecutableElement overridden) {
    return overrider.equals(overridden)
        || elementUtils.overrides(
            overrider, overridden, (TypeElement) overrider.getEnclosingElement());
  }

  static boolean isOrOverridesAnyOf(
      Elements elementUtils,
      ExecutableElement overrider,
      ExecutableElement a,
      ExecutableElement b,
      ExecutableElement c) {
    return isOrOverrides(elementUtils, overrider, a)
        || isOrOverrides(elementUtils, overrider, b)
        || isOrOverrides(elementUtils, overrider, c);
  }

  static boolean nameMatches(Element element, String name) {
    return element.getSimpleName().contentEquals(name);
  }

  static boolean nameMatches(AnnotationMirror m, String name) {
    return nameMatches(m.getAnnotationType().asElement(), name);
  }

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
   *
   * TODO(cpovirk): Require a fully qualified class name (e.g., "java.util.Objects" vs. "Objects").
   */
  static boolean nameMatches(Element element, String clazz, String method) {
    return nameMatches(element, method) && nameMatches(element.getEnclosingElement(), clazz);
  }

  /** See caveats on {@link #nameMatches(Element, String, String)}. */
  static boolean nameMatches(MethodInvocationTree tree, String clazz, String method) {
    return nameMatches(elementFromUse(tree), clazz, method);
  }

  /** See caveats on {@link #nameMatches(Element, String, String)}. */
  static boolean nameMatches(MemberReferenceTree tree, String clazz, String method) {
    return nameMatches(elementFromUse(tree), clazz, method);
  }

  static Optional<ExecutableElement> onlyExecutableWithName(
      Optional<TypeElement> type, String name) {
    return type.map(e -> onlyExecutableWithName(e, name));
  }

  static ExecutableElement onlyExecutableWithName(TypeElement type, String name) {
    return onlyExecutableElement(type, name, m -> true);
  }

  static ExecutableElement onlyNoArgExecutableWithName(TypeElement type, String name) {
    return onlyExecutableElement(type, name, m -> m.getParameters().isEmpty());
  }

  static ExecutableElement onlyOneArgExecutableWithName(TypeElement type, String name) {
    return onlyExecutableElement(type, name, m -> m.getParameters().size() == 1);
  }

  static Optional<ExecutableElement> optionalOnlyExecutableWithName(TypeElement type, String name) {
    List<ExecutableElement> elements = executableElements(type, name, m -> true);
    switch (elements.size()) {
      case 0:
        return Optional.empty();
      case 1:
        return Optional.of(elements.get(0));
      default:
        throw new IllegalArgumentException(type + "." + name);
    }
  }

  private static List<ExecutableElement> executableElements(
      TypeElement type, String name, Predicate<ExecutableElement> predicate) {
    return type.getEnclosedElements().stream()
        .filter(ExecutableElement.class::isInstance)
        .map(ExecutableElement.class::cast)
        .filter(x -> nameMatches(x, name))
        .filter(predicate)
        .collect(toList());
  }

  private static ExecutableElement onlyExecutableElement(
      TypeElement type, String name, Predicate<ExecutableElement> predicate) {
    List<ExecutableElement> elements = executableElements(type, name, predicate);
    if (elements.size() != 1) {
      throw new IllegalArgumentException(type + "." + name);
    }
    return elements.get(0);
  }

  static final Set<TypeUseLocation> IMPLEMENTATION_VARIABLE_LOCATIONS =
      unmodifiableSet(
          new HashSet<>(
              asList(
                  TypeUseLocation.LOCAL_VARIABLE,
                  TypeUseLocation.RESOURCE_VARIABLE,
                  TypeUseLocation.EXCEPTION_PARAMETER)));

  static final Set<ElementKind> IMPLEMENTATION_VARIABLE_KINDS =
      unmodifiableSet(
          new HashSet<>(
              asList(
                  ElementKind.LOCAL_VARIABLE,
                  ElementKind.RESOURCE_VARIABLE,
                  ElementKind.EXCEPTION_PARAMETER)));

  static boolean hasSuppressWarningsNullness(
      List<? extends AnnotationTree> annotations,
      TypeMirror javaLangSuppressWarnings,
      ExecutableElement suppressWarningsValueElement,
      Types types) {
    for (AnnotationMirror annotation : annotationsFromTypeAnnotationTrees(annotations)) {
      if (isSuppressWarningsNullness(
          annotation, javaLangSuppressWarnings, suppressWarningsValueElement, types)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSuppressWarningsNullness(
      AnnotationMirror annotation,
      TypeMirror javaLangSuppressWarnings,
      ExecutableElement suppressWarningsValueElement,
      Types types) {
    if (!types.isSameType(
        annotation.getAnnotationType().asElement().asType(), javaLangSuppressWarnings)) {
      return false;
    }
    boolean[] isSuppression = new boolean[1];
    new SimpleAnnotationValueVisitor8<Void, Void>() {
      @Override
      public Void visitString(String s, Void unused) {
        isSuppression[0] |= s.equals("nullness");
        return null;
      }

      @Override
      public Void visitArray(List<? extends AnnotationValue> vals, Void unused) {
        vals.forEach(v -> v.accept(this, null));
        return null;
      }
    }.visit(annotation.getElementValues().get(suppressWarningsValueElement));
    return isSuppression[0];
  }

  private Util() {}
}
