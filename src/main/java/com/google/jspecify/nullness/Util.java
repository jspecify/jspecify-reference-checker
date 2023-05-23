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
import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toList;
import static org.checkerframework.javacutil.TreeUtils.annotationsFromTypeAnnotationTrees;
import static org.checkerframework.javacutil.TreeUtils.elementFromUse;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import org.checkerframework.javacutil.AnnotationBuilder;

final class Util {
  private final Elements elementUtils;
  private final Types types;

  final AnnotationMirror minusNull;
  final AnnotationMirror unionNull;
  final AnnotationMirror nullnessOperatorUnspecified;

  final TypeElement javaUtilCollectionElement;
  final ExecutableElement collectionToArrayNoArgElement;
  final TypeElement javaUtilMapElement;
  final ExecutableElement mapGetOrDefaultElement;
  final TypeMirror javaUtilConcurrentExecutionException;
  final TypeMirror uncheckedExecutionException;
  final TypeElement javaLangClassElement;
  final Optional<ExecutableElement> classGetEnumConstantsElement;
  final TypeMirror javaLangClassOfExtendsEnum;
  final Optional<ExecutableElement> classIsAnonymousClassElement;
  final Optional<ExecutableElement> classIsMemberClassElement;
  final Optional<ExecutableElement> classGetEnclosingClassElement;
  final ExecutableElement classIsArrayElement;
  final ExecutableElement classGetComponentTypeElement;
  final Optional<ExecutableElement> annotatedElementIsAnnotationPresentElement;
  final Optional<ExecutableElement> annotatedElementGetAnnotationElement;
  final TypeElement javaLangThreadLocalElement;
  final Optional<ExecutableElement> threadLocalInitialValueElement;
  final Optional<TypeMirror> javaNioFileDrectoryStream;
  final Optional<ExecutableElement> pathGetFileNameElement;
  final ExecutableElement mapKeySetElement;
  final ExecutableElement mapContainsKeyElement;
  final ExecutableElement mapGetElement;
  final ExecutableElement mapPutElement;
  final ExecutableElement mapRemoveElement;
  final ExecutableElement navigableMapNavigableKeySetElement;
  final ExecutableElement navigableMapDescendingKeySetElement;
  final ExecutableElement objectsToStringTwoArgElement;
  final Optional<ExecutableElement> converterConvertElement;
  final Optional<ExecutableElement> optionalToJavaUtilElement;
  final Optional<ExecutableElement> optionalFromJavaUtilElement;
  final Map<ExecutableElement, ExecutableElement> getterForSetter;

  private final TypeMirror javaLangSuppressWarnings;
  private final ExecutableElement suppressWarningsValueElement;

  Util(Elements elementUtils, Types types) {
    this.elementUtils = elementUtils;
    this.types = types;

    Elements e = elementUtils;
    /*
     * Under our proposed subtyping rules, every type has a "nullness operator." There are 4
     * nullness-operator values. In this implementation, we *mostly* represent each one with an
     * AnnotationMirror for an annotation that is private to this checker.
     *
     * There is one exception: We do not have an AnnotationMirror for the nullness operator
     * NO_CHANGE. When we need to represent NO_CHANGE, we take one of two approaches, depending on
     * the base type:
     *
     * - On type-variable usage, we use *no* annotation.
     *
     * - On other types, we use minusNull.
     *
     * For further discussion of this, see isNullExclusiveUnderEveryParameterization.
     *
     * Since the proposed subtyping rules use names like "UNION_NULL," we follow those names here.
     * Still, we give the underlying classes names like "Nullable": If those names show up in error
     * messages somehow (unlikely, since we provide our own formatter for types), we want them to
     * match the names of the user-facing JSpecify annotations.
     */
    minusNull = AnnotationBuilder.fromClass(e, MinusNull.class);
    unionNull = AnnotationBuilder.fromClass(e, Nullable.class);
    nullnessOperatorUnspecified = AnnotationBuilder.fromClass(e, NullnessUnspecified.class);
    /*
     * Note that all the above annotations must be on the *classpath*, not just the *processorpath*.
     * That's because, even if we change fromClass to fromName, AnnotationBuilder ultimately calls
     * elements.getTypeElement.
     *
     * That's unfortunate. It would be nice if we didn't need a full AnnotationMirror at all, only a
     * class name. But AnnotationMirror is baked into CF deeply, since CF needs to support
     * generalized annotation types, write annotations back to bytecode, and perhaps more.
     *
     * We do at least avoid requiring the _user-facing_ JSpecify annotations to be present on the
     * classpath. To accomplish that, we represent types internally by using our own package-private
     * annotations instead: We consider the user-facing annotations to be mere "aliases" for those.
     * While it's still unfortunate to have to add *anything* to the classpath in order to run the
     * checker, we at least avoid adding user-facing nullness annotations. (Those annotations might
     * conflict with the "real" copies of the annotations. Plus, they might trigger conditional
     * logic in annotation processors, logic that runs only when the JSpecify annotations are
     * present.)
     *
     * TODO(b/187113128): See if we can keep even our internal annotations (and various other
     * annotations required by CF) off the classpath.
     */

    javaUtilCollectionElement = e.getTypeElement("java.util.Collection");
    collectionToArrayNoArgElement =
        onlyNoArgExecutableWithName(javaUtilCollectionElement, "toArray");
    javaUtilMapElement = e.getTypeElement("java.util.Map");
    mapGetOrDefaultElement = onlyExecutableWithName(javaUtilMapElement, "getOrDefault");
    javaUtilConcurrentExecutionException =
        e.getTypeElement("java.util.concurrent.ExecutionException").asType();
    TypeElement uncheckedExecutionExceptionElement =
        e.getTypeElement("com.google.common.util.concurrent.UncheckedExecutionException");
    uncheckedExecutionException =
        uncheckedExecutionExceptionElement == null
            ? null
            : uncheckedExecutionExceptionElement.asType();
    javaLangClassElement = e.getTypeElement("java.lang.Class");
    classGetEnumConstantsElement =
        optionalOnlyExecutableWithName(javaLangClassElement, "getEnumConstants");
    classIsAnonymousClassElement =
        optionalOnlyExecutableWithName(javaLangClassElement, "isAnonymousClass");
    classIsMemberClassElement =
        optionalOnlyExecutableWithName(javaLangClassElement, "isMemberClass");
    classGetEnclosingClassElement =
        optionalOnlyExecutableWithName(javaLangClassElement, "getEnclosingClass");
    classIsArrayElement = onlyExecutableWithName(javaLangClassElement, "isArray");
    classGetComponentTypeElement = onlyExecutableWithName(javaLangClassElement, "getComponentType");
    TypeElement javaLangEnumElement = e.getTypeElement("java.lang.Enum");
    javaLangClassOfExtendsEnum =
        types.getDeclaredType(
            javaLangClassElement,
            types.getWildcardType(types.erasure(javaLangEnumElement.asType()), null));
    javaLangThreadLocalElement = e.getTypeElement("java.lang.ThreadLocal");
    threadLocalInitialValueElement =
        optionalOnlyExecutableWithName(javaLangThreadLocalElement, "initialValue");
    javaNioFileDrectoryStream =
        optionalTypeElement(e, "java.nio.file.DirectoryStream").map(TypeElement::asType);

    pathGetFileNameElement =
        onlyExecutableWithName(optionalTypeElement(e, "java.nio.file.Path"), "getFileName");

    mapKeySetElement = onlyExecutableWithName(javaUtilMapElement, "keySet");
    mapContainsKeyElement = onlyExecutableWithName(javaUtilMapElement, "containsKey");
    mapGetElement = onlyExecutableWithName(javaUtilMapElement, "get");
    mapPutElement = onlyExecutableWithName(javaUtilMapElement, "put");
    mapRemoveElement = onlyOneArgExecutableWithName(javaUtilMapElement, "remove");

    TypeElement javaUtilNavigableMapElement = e.getTypeElement("java.util.NavigableMap");
    navigableMapNavigableKeySetElement =
        onlyExecutableWithName(javaUtilNavigableMapElement, "navigableKeySet");
    navigableMapDescendingKeySetElement =
        onlyExecutableWithName(javaUtilNavigableMapElement, "descendingKeySet");

    TypeElement javaUtilObjectsElement = e.getTypeElement("java.util.Objects");
    objectsToStringTwoArgElement = onlyTwoArgExecutableWithName(javaUtilObjectsElement, "toString");

    Optional<TypeElement> javaLangReflectAnnotatedElementElement =
        optionalTypeElement(e, "java.lang.reflect.AnnotatedElement");
    annotatedElementIsAnnotationPresentElement =
        onlyExecutableWithName(javaLangReflectAnnotatedElementElement, "isAnnotationPresent");
    annotatedElementGetAnnotationElement =
        onlyExecutableWithName(javaLangReflectAnnotatedElementElement, "getAnnotation");
    converterConvertElement =
        onlyExecutableWithName(
            optionalTypeElement(e, "com.google.common.base.Converter"), "convert");
    Optional<TypeElement> comGoogleCommonBaseOptionalElement =
        optionalTypeElement(e, "com.google.common.base.Optional");
    // The conversion methods aren't available if we're compiling against guava-android.
    optionalToJavaUtilElement =
        optionalOnlyOneArgExecutableWithName(comGoogleCommonBaseOptionalElement, "toJavaUtil");
    optionalFromJavaUtilElement =
        optionalOnlyExecutableWithName(comGoogleCommonBaseOptionalElement, "fromJavaUtil");

    Map<ExecutableElement, ExecutableElement> getterForSetter = new HashMap<>();
    TypeElement uriBuilderElement = e.getTypeElement("com.google.common.net.UriBuilder");
    if (uriBuilderElement != null) {
      put(getterForSetter, uriBuilderElement, "setScheme", "getScheme");
      put(getterForSetter, uriBuilderElement, "setAuthority", "getAuthority");
      put(getterForSetter, uriBuilderElement, "setPath", "getPath");
      // NOT query: After setQuery(""), getQuery() returns null :(
      put(getterForSetter, uriBuilderElement, "setFragment", "getFragment");
    }
    this.getterForSetter = unmodifiableMap(getterForSetter);

    TypeElement javaLangSuppressWarningsElement = e.getTypeElement("java.lang.SuppressWarnings");
    javaLangSuppressWarnings = javaLangSuppressWarningsElement.asType();
    suppressWarningsValueElement = onlyExecutableWithName(javaLangSuppressWarningsElement, "value");

    /*
     * TODO(cpovirk): Initialize some of these lazily if they cause performance problems.
     *
     * Lazy initialization could conceivably also help with environments that are missing some APIs,
     * like j2cl (b/189946810).
     */
  }

  static Optional<TypeElement> optionalTypeElement(Elements e, String name) {
    return Optional.ofNullable(e.getTypeElement(name));
  }

  boolean isOrOverrides(ExecutableElement overrider, ExecutableElement overridden) {
    return overrider.equals(overridden)
        || elementUtils.overrides(
            overrider, overridden, (TypeElement) overrider.getEnclosingElement());
  }

  boolean isOrOverridesAnyOf(
      ExecutableElement overrider, ExecutableElement a, ExecutableElement b, ExecutableElement c) {
    return isOrOverrides(overrider, a)
        || isOrOverrides(overrider, b)
        || isOrOverrides(overrider, c);
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

  private static Optional<ExecutableElement> onlyExecutableWithName(
      Optional<TypeElement> type, String name) {
    return type.map(e -> onlyExecutableWithName(e, name));
  }

  private static ExecutableElement onlyExecutableWithName(TypeElement type, String name) {
    return onlyExecutableElement(type, name, m -> true);
  }

  private static ExecutableElement onlyNoArgExecutableWithName(TypeElement type, String name) {
    return onlyExecutableElement(type, name, m -> m.getParameters().isEmpty());
  }

  private static ExecutableElement onlyOneArgExecutableWithName(TypeElement type, String name) {
    return onlyExecutableElement(type, name, m -> m.getParameters().size() == 1);
  }

  private static ExecutableElement onlyTwoArgExecutableWithName(TypeElement type, String name) {
    return onlyExecutableElement(type, name, m -> m.getParameters().size() == 2);
  }

  private static Optional<ExecutableElement> optionalOnlyExecutableWithName(
      TypeElement type, String name) {
    return optionalOnlyExecutableElement(type, name, m -> true);
  }

  private static Optional<ExecutableElement> optionalOnlyExecutableWithName(
      Optional<TypeElement> type, String name) {
    return type.flatMap(e -> optionalOnlyExecutableWithName(e, name));
  }

  private static Optional<ExecutableElement> optionalOnlyOneArgExecutableWithName(
      TypeElement type, String name) {
    return optionalOnlyExecutableElement(type, name, m -> m.getParameters().size() == 1);
  }

  private static Optional<ExecutableElement> optionalOnlyOneArgExecutableWithName(
      Optional<TypeElement> type, String name) {
    return type.flatMap(e -> optionalOnlyOneArgExecutableWithName(e, name));
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
    return optionalOnlyExecutableElement(type, name, predicate).get();
  }

  private static Optional<ExecutableElement> optionalOnlyExecutableElement(
      TypeElement type, String name, Predicate<ExecutableElement> predicate) {
    List<ExecutableElement> elements = executableElements(type, name, predicate);
    switch (elements.size()) {
      case 0:
        return Optional.empty();
      case 1:
        return Optional.of(elements.get(0));
      default:
        throw new IllegalArgumentException(type + "." + name);
    }
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

  boolean hasSuppressWarningsNullness(List<? extends AnnotationTree> annotations) {
    for (AnnotationMirror annotation : annotationsFromTypeAnnotationTrees(annotations)) {
      if (isSuppressWarningsNullness(annotation)) {
        return true;
      }
    }
    return false;
  }

  private boolean isSuppressWarningsNullness(AnnotationMirror annotation) {
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

  private static void put(
      Map<ExecutableElement, ExecutableElement> getterForSetter,
      TypeElement type,
      String setter,
      String getter) {
    getterForSetter.put(
        onlyExecutableWithName(type, setter), onlyNoArgExecutableWithName(type, getter));
  }
}
