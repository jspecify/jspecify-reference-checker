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

import static com.google.jspecify.nullness.Util.nameMatches;
import static com.google.jspecify.nullness.Util.onlyExecutableWithName;
import static com.google.jspecify.nullness.Util.onlyNoArgExecutableWithName;
import static com.google.jspecify.nullness.Util.onlyOneArgExecutableWithName;
import static com.google.jspecify.nullness.Util.onlyTwoArgExecutableWithName;
import static com.google.jspecify.nullness.Util.optionalOnlyExecutableWithName;
import static com.google.jspecify.nullness.Util.optionalTypeElement;
import static com.sun.source.tree.Tree.Kind.NULL_LITERAL;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toList;
import static org.checkerframework.dataflow.expression.JavaExpression.fromNode;
import static org.checkerframework.framework.flow.CFAbstractStore.canInsertJavaExpression;
import static org.checkerframework.framework.type.AnnotatedTypeMirror.createType;
import static org.checkerframework.framework.util.AnnotatedTypes.asSuper;
import static org.checkerframework.javacutil.AnnotationUtils.areSame;
import static org.checkerframework.javacutil.TreeUtils.elementFromDeclaration;
import static org.checkerframework.javacutil.TreeUtils.elementFromTree;
import static org.checkerframework.javacutil.TreeUtils.elementFromUse;
import static org.checkerframework.javacutil.TreeUtils.typeOf;

import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.checkerframework.dataflow.analysis.ConditionalTransferResult;
import org.checkerframework.dataflow.analysis.TransferInput;
import org.checkerframework.dataflow.analysis.TransferResult;
import org.checkerframework.dataflow.cfg.node.AssignmentNode;
import org.checkerframework.dataflow.cfg.node.BinaryOperationNode;
import org.checkerframework.dataflow.cfg.node.EqualToNode;
import org.checkerframework.dataflow.cfg.node.ExplicitThisNode;
import org.checkerframework.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.dataflow.cfg.node.ImplicitThisNode;
import org.checkerframework.dataflow.cfg.node.InstanceOfNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.NotEqualNode;
import org.checkerframework.dataflow.cfg.node.StringLiteralNode;
import org.checkerframework.dataflow.cfg.node.TypeCastNode;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.dataflow.expression.MethodCall;
import org.checkerframework.framework.flow.CFAbstractAnalysis;
import org.checkerframework.framework.flow.CFAbstractTransfer;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.javacutil.AnnotationBuilder;

final class NullSpecTransfer extends CFAbstractTransfer<CFValue, NullSpecStore, NullSpecTransfer> {
  private final NullSpecAnnotatedTypeFactory atypeFactory;
  private final AnnotationMirror minusNull;
  private final AnnotationMirror nullnessOperatorUnspecified;
  private final AnnotationMirror unionNull;
  private final Optional<TypeMirror> javaNioFileDrectoryStream;
  private final Optional<ExecutableElement> pathGetFileNameElement;
  private final AnnotatedDeclaredType javaUtilMap;
  private final ExecutableElement mapKeySetElement;
  private final ExecutableElement mapContainsKeyElement;
  private final ExecutableElement mapGetElement;
  private final ExecutableElement mapPutElement;
  private final ExecutableElement mapRemoveElement;
  private final ExecutableElement navigableMapNavigableKeySetElement;
  private final ExecutableElement navigableMapDescendingKeySetElement;
  private final ExecutableElement objectsToStringTwoArgElement;
  private final AnnotatedDeclaredType javaLangClass;
  private final Optional<ExecutableElement> classIsAnonymousClassElement;
  private final Optional<ExecutableElement> classIsMemberClassElement;
  private final Optional<ExecutableElement> classGetEnclosingClassElement;
  private final ExecutableElement classIsArrayElement;
  private final ExecutableElement classGetComponentTypeElement;
  private final Optional<ExecutableElement> annotatedElementIsAnnotationPresentElement;
  private final Optional<ExecutableElement> annotatedElementGetAnnotationElement;
  private final Map<ExecutableElement, ExecutableElement> getterForSetter;

  NullSpecTransfer(CFAbstractAnalysis<CFValue, NullSpecStore, NullSpecTransfer> analysis) {
    super(analysis);
    atypeFactory = (NullSpecAnnotatedTypeFactory) analysis.getTypeFactory();
    Elements e = atypeFactory.getElementUtils();

    minusNull = AnnotationBuilder.fromClass(e, MinusNull.class);
    nullnessOperatorUnspecified = AnnotationBuilder.fromClass(e, NullnessUnspecified.class);
    unionNull = AnnotationBuilder.fromClass(e, Nullable.class);

    javaNioFileDrectoryStream =
        optionalTypeElement(e, "java.nio.file.DirectoryStream").map(TypeElement::asType);

    pathGetFileNameElement =
        onlyExecutableWithName(optionalTypeElement(e, "java.nio.file.Path"), "getFileName");

    TypeElement javaUtilMapElement = e.getTypeElement("java.util.Map");
    javaUtilMap =
        (AnnotatedDeclaredType)
            createType(javaUtilMapElement.asType(), atypeFactory, /*isDeclaration=*/ false);
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

    TypeElement javaLangClassElement = e.getTypeElement("java.lang.Class");
    javaLangClass =
        (AnnotatedDeclaredType)
            createType(javaLangClassElement.asType(), atypeFactory, /*isDeclaration=*/ false);
    classIsAnonymousClassElement =
        optionalOnlyExecutableWithName(javaLangClassElement, "isAnonymousClass");
    classIsMemberClassElement =
        optionalOnlyExecutableWithName(javaLangClassElement, "isMemberClass");
    classGetEnclosingClassElement =
        optionalOnlyExecutableWithName(javaLangClassElement, "getEnclosingClass");
    classIsArrayElement = onlyExecutableWithName(javaLangClassElement, "isArray");
    classGetComponentTypeElement = onlyExecutableWithName(javaLangClassElement, "getComponentType");

    Optional<TypeElement> javaLangReflectAnnotatedElementElement =
        optionalTypeElement(e, "java.lang.reflect.AnnotatedElement");
    annotatedElementIsAnnotationPresentElement =
        onlyExecutableWithName(javaLangReflectAnnotatedElementElement, "isAnnotationPresent");
    annotatedElementGetAnnotationElement =
        onlyExecutableWithName(javaLangReflectAnnotatedElementElement, "getAnnotation");

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
  }

  private static void put(
      Map<ExecutableElement, ExecutableElement> getterForSetter,
      TypeElement type,
      String setter,
      String getter) {
    getterForSetter.put(
        onlyExecutableWithName(type, setter), onlyNoArgExecutableWithName(type, getter));
  }

  @Override
  public TransferResult<CFValue, NullSpecStore> visitFieldAccess(
      FieldAccessNode node, TransferInput<CFValue, NullSpecStore> input) {
    TransferResult<CFValue, NullSpecStore> result = super.visitFieldAccess(node, input);
    if (node.getFieldName().equals("class")) {
      /*
       * TODO(cpovirk): Move our "non-dataflow" special cases out of NullSpecTransfer and into our
       * TreeAnnotator.
       *
       * *Or*: Would an even *better* option be to override a method like fromElement or
       * getAnnotatedType? While methods like TreeAnnotator.visitMethodInvocation handle specific
       * kinds of usages (like, well, "method invocations"), a method like fromElement or
       * getAnnotatedType could handle all kinds of usages (including method references). Or maybe
       * that's bad: When we analyze a method implementation in com.google.common itself, we
       * probably don't want the checker to ignore the annotations that we've written on, e.g., its
       * return type in favor of the "pretend" annotations that we apply for users here. On top of
       * all that, another potentially tricky bit is to ensure that we have access to all the
       * information we need. In particular, we sometimes need a Tree, not just an Element, such as
       * when we look for calls to Throwable.getCause() that happens specifically on an instance of
       * ExecutionException.
       *
       * *Or*: Would we prefer to just define stub files, since we're essentially recreating them
       * here? However, we've seen slowness from using -Astubs (perhaps solvable by building the
       * stub files into the Checker Framework itself, similar to the JDK stubs), *and* we've seen
       * crashes (at least with our custom checker) when we analyze a codebase that we supply stubs
       * for. Even if that doesn't work, though, maybe we should look to hook into CF in similar
       * places to where the stub-file logic appears?
       *
       * Anyway, TreeAnnotator (or any other approach discussed above) offers advantages over
       * NullSpecTransfer:
       *
       * - It runs in cases in which NullSpecTransfer (somewhat mysteriously) does not. See
       *   https://github.com/jspecify/jspecify/blob/ef834e47eb83cdf3fb912d07705aa40808584a6a/samples/GetCauseNonNull.java#L46
       *
       * - TreeAnnotator lets us change the nullness of a _non-top-level_ type. Currently, we do
       *   this to change the element type of a Stream when we know that it is non-nullable. (Aside:
       *   Another piece of that logic -- well, a somewhat different piece of logic with a similar
       *   purpose -- lives in checkMethodReferenceAsOverride. So that logic is already split across
       *   files.)
       *
       * - I haven't measured performance, but I would assume that anything that runs during
       *   dataflow is not going to be *faster* :) Perhaps more importantly, if we ever try to
       *   improve performance by skipping dataflow in cases in which we can prove what we need
       *   without it (as NullAway does), then we'll want to move our handling for as many simple
       *   cases as we can to run it outside of dataflow.
       *
       * - Hypothetically, TreeAnnotator would make more sense if we needed to change types that
       *   appear in "non-dataflow" locations -- perhaps if we needed to change the types of a
       *   method's parameters or return type before overload checking occurs? But I don't know that
       *   we'll need to do that.
       */
      setResultValueToNonNull(result);
    }
    return result;
  }

  @Override
  public TransferResult<CFValue, NullSpecStore> visitMethodInvocation(
      MethodInvocationNode node, TransferInput<CFValue, NullSpecStore> input) {
    TransferResult<CFValue, NullSpecStore> result = super.visitMethodInvocation(node, input);
    NullSpecStore thenStore = input.getThenStore();
    NullSpecStore elseStore = input.getElseStore();
    ExecutableElement method = node.getTarget().getMethod();

    boolean storeChanged = false;

    if (nameMatches(method, "Objects", "requireNonNull")
        || nameMatches(method, "Preconditions", "checkNotNull")
        || nameMatches(method, "Verify", "verifyNotNull")) {
      // See the discussion of checkState and checkArgument below.
      storeChanged |= refineNonNull(node.getArgument(0), thenStore);
      storeChanged |= refineNonNull(node.getArgument(0), elseStore);
    }

    if (nameMatches(method, "Class", "isInstance")) {
      storeChanged |= refineNonNull(node.getArgument(0), thenStore);
    }

    if (nameMatches(method, "Strings", "isNullOrEmpty")) {
      storeChanged |= refineNonNull(node.getArgument(0), elseStore);
    }

    if (isGetCanonicalNameOnClassLiteral(node)) {
      setResultValueToNonNull(result);
    }

    if (isGetClassLoaderClassLiteral(node)) {
      /*
       * getClassLoader can return null for classes from the bootstrap class loader. Here, we assume
       * that it returns non-null -- but only if it's called on a class literal. That assumption is
       * still unsound, but it at least ensures (outside of unusual cases) that a given call to
       * getClassLoader will either always return null or never do so. Users may be surprised and
       * annoyed if they find that we don't catch the problem with String.class.getClassLoader(),
       * but those users are likely to be surprised and annoyed by the null return regardless. We'd
       * still ideally not make their experience *worse*, but I think it's worth trying as a
       * pragmatic compromise, given that the alternative is to produce an error for common, safe
       * code like `MyService.class.getClassLoader().getResource("foo")`.
       */
      setResultValueToNonNull(result);
    }

    if (isGetClassLoaderClassOnThisGetClass(node)) {
      /*
       * This is similar to the case above but even less likely to be a problem in practice: Unless
       * the call is occurring in a class that is itself loaded by the bootstrap class loader, the
       * return value will be non-null.
       */
      setResultValueToNonNull(result);
    }

    if (isGetThreadGroupOnCurrentThread(node)) {
      setResultValueToNonNull(result);
    }

    if (isGetSuperclassOnGetClass(node)) {
      setResultValueToNonNull(result);
    }

    if (isReflectiveRead(node)) {
      /*
       * Calls to Method.invoke and Field.get can most certainly return null, so we've annotated
       * those methods accordingly. Still, we're finding that, in practice, users don't benefit much
       * from being forced to null-check their results. So we're experimenting with assuming that
       * they return non-null.
       *
       * Note that we want to be sure that subsequent code can "demote" the result back to being
       * @Nullable. That is, inside `if (result == null)`, `result` had better be @Nullable, even
       * though we previously assumed it was non-null. This happens to work with our current
       * implementation, but it's easy to see how another implementation could set the type to
       * "bottom" in that case and thus not issue the errors that we'd like to see.
       *
       * If we find that the non-null assumption here prevents "demoting," then we could consider
       * changing this code to assume a return type with _unspecified_ nullness instead. (This of
       * course assumes that "demoting" _from unspecified nullness_ to @Nullable continues to work!)
       * Arguably this is a better option, anyway, since it lets users of "strict mode" see errors
       * in that case. (But we suspect that few users, if any, will use strict mode. So this is
       * mostly academic.) It also causes us to show the type in error messages as Object* instead
       * of Object, which is arguably better (though more noisy). Finally, if we track metrics for
       * the percentage of our calls in the depot for which we have nullness information, it seems
       * more honest to treat these cases like unspecified nullness, since we're not actually
       * providing nullness checking for them.
       *
       * The Right Way to address "demoting" is probably to avoid it entirely. Instead, we should
       * continue to track the result as @Nullable, but we should also track an additional "but
       * don't issue errors about this" bit. However, this likely would require a bunch of design
       * work. Plus, it's hard to see how we could communicate to users that a particular type
       * component "doesn't matter" when printing an error message about a mismatch in some *other*
       * type component.
       *
       * Note that this concern about "demoting" could likewise apply to the other methods that we
       * unsoundly assume are non-null above. I mention the concern here simply because reflective
       * reads are the case in which null values seem most likely in practice.
       *
       * (OK, there's maybe a fuzzy argument that reflective reads "really have" unspecified
       * nullness in a sense, since they are "really" a read from a field or method whose nullness
       * information JSpecify doesn't have its normal access to. But I don't think we want to go
       * down that road: After all, for almost _any_ @Nullable-returning API, we could make an
       * argument that the caller "doesn't have access to the information it needs to determine if
       * the value may be null." That's almost what @Nullable means: "It's null sometimes, and other
       * times, it's not" :))
       */
      setResultValueToNonNull(result);
    }

    if ((nameMatches(method, "Preconditions", "checkState")
            || nameMatches(method, "Preconditions", "checkArgument"))
        && node.getArgument(0) instanceof NotEqualNode) {
      NotEqualNode notEqualNode = (NotEqualNode) node.getArgument(0);
      /*
       * `check*(x != null)` doesn't return a value, so CF might look at thenStore, elseStore, or
       * both. Fortunately, we can set x to non-null in both cases:
       *
       * - If `check*(x != null)` succeeds, then we've proven that x is non-null.
       *
       * - If `check*(x != null)` fails, then it will throw an exception. So it's safe to consider x
       * to have whatever value we want.
       *
       * TODO(cpovirk): Is that actually safe? Does it handle the case in which someone catches the
       * IllegalStateException/IllegalArgumentException? If not, then we likely also have the same
       * issue with our handling of requireNonNull.
       */
      storeChanged |= storeNonNullIfComparesToNull(notEqualNode, thenStore);
      storeChanged |= storeNonNullIfComparesToNull(notEqualNode, elseStore);
    }

    if (nameMatches(method, "Class", "cast")
        || nameMatches(method, "Optional", "orElse")
        || nameMatches(method, "Converter", "convert")) {
      AnnotatedTypeMirror type = typeWithTopLevelAnnotationsOnly(input, node.getArgument(0));
      if (atypeFactory.withLeastConvenientWorld().isNullExclusiveUnderEveryParameterization(type)) {
        setResultValueToNonNull(result);
      } else if (atypeFactory
          .withMostConvenientWorld()
          .isNullExclusiveUnderEveryParameterization(type)) {
        /*
         * If T has a non-null bound -- as it does in our current declarations of the types we're
         * currently handling here -- then returning `@NullnessUnspecified T` is correct.
         *
         * If T has an unspecified bound, then we may return `@NullnessUnspecified T` when we ought
         * to have returned a plain `T`. Fortunately, this would matter only in strict mode.
         *
         * If T has a nullable bound, then returning `@NullnessUnspecified T` would not accomplish
         * what we want: We want a type that is null-exclusive in lenient mode, but
         * `@NullnessUnspecified T` does not accomplish that when T has a nullable bound. If we
         * wanted to handle that case, we'd need to enhance our model to support an additional
         * nullness operator that "projects" to unspecified nullness, just as @NonNull "projects" to
         * non-null, regardless of what the type variable it's applied to otherwise permits.
         */
        setResultValueOperatorToUnspecified(result);
      }
    } else if (method.equals(objectsToStringTwoArgElement)) {
      // Just like the case above but for arg 1 instead of arg 0.
      AnnotatedTypeMirror type = typeWithTopLevelAnnotationsOnly(input, node.getArgument(1));
      if (atypeFactory.withLeastConvenientWorld().isNullExclusiveUnderEveryParameterization(type)) {
        setResultValueToNonNull(result);
      } else if (atypeFactory
          .withMostConvenientWorld()
          .isNullExclusiveUnderEveryParameterization(type)) {
        setResultValueOperatorToUnspecified(result);
      }
    } else if (nameMatches(method, "System", "getProperty")) {
      Node arg = node.getArgument(0);
      if (arg instanceof StringLiteralNode
          && ALWAYS_PRESENT_PROPERTY_VALUES.contains(((StringLiteralNode) arg).getValue())) {
        // TODO(cpovirk): Also handle other compile-time constants (concat, static final fields).
        /*
         * This assumption is not *completely* safe, since users can clear property values. But I
         * feel OK with that risk.
         *
         * This assumption is also not safe under GWT, but perhaps GWT has its own compile-time
         * check to reject non-GWT-recognized properties?
         */
        setResultValueToNonNull(result);
      }
    } else if (nameMatches(method, "StandardSystemProperty", "value")) {
      /*
       * The following is not completely safe -- not only for the reason discussed in the handling
       * of System.getProperty itself above but also because StandardSystemProperty provides
       * constants for properties that are not always present.
       *
       * TODO(cpovirk): Be more conservative for at least the known-not-to-be-present properties.
       */
      setResultValueToNonNull(result);
    } else if (nameMatches(method, "Class", "getPackage")) {
      // This is not sound, but it's very likely to be safe inside Google.
      setResultValueToNonNull(result);
    } else if (nameMatches(method, "Matcher", "group")) {
      // Also not sound, but the alternative is a lot of noise.
      setResultValueToNonNull(result);
    }

    for (Entry<ExecutableElement, ExecutableElement> entry : getterForSetter.entrySet()) {
      if (method.equals(entry.getKey())) {
        storeChanged |=
            overwriteGetterFromSetter(
                node,
                entry.getValue(),
                input.getValueOfSubNode(node.getArgument(0)),
                thenStore,
                elseStore);
      }
    }

    if (isOrOverrides(method, pathGetFileNameElement)) {
      refinePathGetFileNameResultIfDirectoryStreamLoop(node, result);
    }

    if (isOrOverrides(method, mapGetElement)) {
      refineMapGetResultIfKeySetLoop(node, result);
    }

    if (isOrOverrides(method, mapContainsKeyElement)) {
      storeChanged |= refineFutureMapGetFromMapContainsKeyOrPut(node, thenStore);
    }

    if (isOrOverrides(method, mapPutElement)) {
      storeChanged |= refineFutureMapGetFromMapContainsKeyOrPut(node, thenStore);
      storeChanged |= refineFutureMapGetFromMapContainsKeyOrPut(node, elseStore);
    }

    if (isOrOverrides(method, annotatedElementIsAnnotationPresentElement)) {
      storeChanged |= refineFutureGetAnnotationFromIsAnnotationPresent(node, thenStore);
    }

    if (isOrOverrides(method, classIsAnonymousClassElement)
        || isOrOverrides(method, classIsMemberClassElement)) {
      storeChanged |= refineFutureGetEnclosingClassFromIsEnclosedClass(node, thenStore);
    }

    if (isOrOverrides(method, classIsArrayElement)) {
      storeChanged |= refineFutureGetComponentTypeFromIsArray(node, thenStore);
    }

    return new ConditionalTransferResult<>(
        result.getResultValue(), thenStore, elseStore, storeChanged);
  }

  private boolean overwriteGetterFromSetter(
      MethodInvocationNode setterNode,
      ExecutableElement getter,
      CFValue argument,
      NullSpecStore thenStore,
      NullSpecStore elseStore) {
    boolean storeChanged = false;
    storeChanged |= overwriteGetterFromSetter(setterNode, getter, argument, thenStore);
    storeChanged |= overwriteGetterFromSetter(setterNode, getter, argument, elseStore);
    return storeChanged;
  }

  private boolean overwriteGetterFromSetter(
      MethodInvocationNode setterNode,
      ExecutableElement getter,
      CFValue argument,
      NullSpecStore store) {
    MethodCall setterCall = (MethodCall) fromNode(setterNode);
    MethodCall getterCall =
        new MethodCall(
            argument.getUnderlyingType(),
            getter,
            setterCall.getReceiver(),
            /*arguments=*/ emptyList());
    return overwrite(getterCall, argument, store);
  }

  private boolean refineFutureGetEnclosingClassFromIsEnclosedClass(
      MethodInvocationNode isEnclosedClassNode, NullSpecStore thenStore) {
    // TODO(cpovirk): Reduce duplication between this and the methods nearby.
    MethodCall isEnclosedClassCall = (MethodCall) fromNode(isEnclosedClassNode);
    MethodCall getEnclosingClassCall =
        new MethodCall(
            javaLangClass.getUnderlyingType(),
            classGetEnclosingClassElement.get(), // present when isAnonymousClass/isMemberClass is
            isEnclosedClassCall.getReceiver(),
            isEnclosedClassCall.getArguments());
    return refine(
        getEnclosingClassCall,
        analysis.createSingleAnnotationValue(minusNull, javaLangClass.getUnderlyingType()),
        thenStore);
  }

  private boolean refineFutureGetComponentTypeFromIsArray(
      MethodInvocationNode isArrayNode, NullSpecStore thenStore) {
    // TODO(cpovirk): Reduce duplication between this and the methods nearby.
    MethodCall isArrayCall = (MethodCall) fromNode(isArrayNode);
    MethodCall getComponentTypeCall =
        new MethodCall(
            javaLangClass.getUnderlyingType(),
            classGetComponentTypeElement,
            isArrayCall.getReceiver(),
            isArrayCall.getArguments());
    return refine(
        getComponentTypeCall,
        analysis.createSingleAnnotationValue(minusNull, javaLangClass.getUnderlyingType()),
        thenStore);
  }

  private boolean refineFutureGetAnnotationFromIsAnnotationPresent(
      MethodInvocationNode isAnnotationPresentNode, NullSpecStore thenStore) {
    // TODO(cpovirk): Reduce duplication between this and refineFutureMapGetFromMapContainsKey.
    Tree isAnnotationPresentReceiver = isAnnotationPresentNode.getTarget().getReceiver().getTree();
    if (isAnnotationPresentReceiver == null) {
      /*
       * See discussion in refineFutureMapGetFromMapContainsKey below. Note that this case should be
       * even rarer than that method's containsKeyReceiver case (an already rare case), since so few
       * classes implement AnnotatedElement.
       */
      return false;
    }

    AnnotatedElementAndAnnotationTypes types =
        new AnnotatedElementAndAnnotationTypes(
            isAnnotationPresentReceiver, isAnnotationPresentNode.getArgument(0).getTree());
    if (types.annotationAsDataflowValue == null) {
      return false;
    }
    MethodCall isAnnotationPresentCall = (MethodCall) fromNode(isAnnotationPresentNode);

    List<ExecutableElement> getAnnotationAndOverrides =
        methodAndOverrides(
            annotatedElementGetAnnotationElement.get(), // present with isAnnotationPresent is
            types.annotatedElementType);

    boolean storeChanged = false;
    for (ExecutableElement getAnnotationAndOverride : getAnnotationAndOverrides) {
      MethodCall getAnnotationCall =
          new MethodCall(
              types.annotationAsDataflowValue.getUnderlyingType(),
              getAnnotationAndOverride,
              isAnnotationPresentCall.getReceiver(),
              isAnnotationPresentCall.getArguments());

      storeChanged |= refine(getAnnotationCall, types.annotationAsDataflowValue, thenStore);
    }
    return storeChanged;
  }

  private final class AnnotatedElementAndAnnotationTypes {
    final AnnotatedTypeMirror annotatedElementType;
    final CFValue annotationAsDataflowValue;

    AnnotatedElementAndAnnotationTypes(
        Tree isAnnotationPresentReceiver, Tree isAnnotationPresentArgument) {
      annotatedElementType = atypeFactory.getAnnotatedType(isAnnotationPresentReceiver);
      AnnotatedTypeMirror argumentType = atypeFactory.getAnnotatedType(isAnnotationPresentArgument);
      /*
       * The argument's static type could be a type-variable type (or a wildcard, probably, since CF
       * doesn't implement capture conversation as of this writing). We need it as a Class<T> so
       * that we can extract the T.
       */
      AnnotatedDeclaredType argumentTypeAsClass =
          asSuper(atypeFactory, argumentType, javaLangClass);
      AnnotatedTypeMirror annotationType = argumentTypeAsClass.getTypeArguments().get(0);
      annotationAsDataflowValue =
          analysis.createAbstractValue(
              annotationType.getAnnotations(), annotationType.getUnderlyingType());
    }
  }

  private boolean refineFutureMapGetFromMapContainsKeyOrPut(
      MethodInvocationNode containsKeyOrPutNode, NullSpecStore store) {
    Tree containsKeyOrPutReceiver = containsKeyOrPutNode.getTarget().getReceiver().getTree();
    if (containsKeyOrPutReceiver == null) {
      /*
       * TODO(cpovirk): Handle the case of a null containsKeyOrPutReceiver (probably
       * ImplicitThisNode).
       */
      return false;
    }

    MapType mapType = new MapType(containsKeyOrPutReceiver);
    if (mapType.mapValueAsDataflowValue == null) {
      /*
       * We failed to create the CFValue we want, so give up.
       *
       * This comes up with unannotated wildcard types, like the return type of a call to get(...)
       * on a Map<Foo, ?>. CF requires a wildcard CFValue to have annotations (unless the wildcard's
       * bounds are type-variable usages). This works out for stock CF because stock CF keeps
       * wildcards' annotations in sync with their bounds' annotations). We, however, typically
       * don't consider wildcards *themselves* to have annotations.
       *
       * I attempted a partial workaround: If the wildcard is known to be null-exclusive, then we
       * can annotated it with NonNull or NullnessUnspecified as appropriate. Because the resulting
       * wildcard then has an annotation, we can create a CFValue for it. And because we checked
       * that it was null-exclusive, our new wildcard is mostly equivalent. However, I ran into a
       * problem: No dataflow refinement has any effect on wildcards, thanks to our override of
       * addComputedTypeAnnotations in NullSpecAnnotatedTypeFactory. Someday we should remove that
       * override, but currently, doing so causes other problems.
       *
       * (If we someday do remove the override, we can try the workaround again. Then again, if we
       * were able to remove the override, that may mean that we've solved the problem at a deeper
       * level, in which case we might not need the workaround anymore. (Maybe the problem will be
       * solved for us when CF implements capture conversion?) Note, though, that if we end up
       * trying the workaround again, we could try doing even better: We could unwrap wildcards into
       * their bound types -- at least in the case of `extends` and unbounded wildcards. (But we'd
       * need to be careful to preserve any annotation "on the wildcard itself.") I'm not entirely
       * sure if CF will permit this, since it would change the expression type. But if it does, it
       * may let us create a CFValue for any wildcard type, since we can create one for (as far as I
       * know) any non-wildcard type, and we can get such a type by unwrapping wildcards.)
       *
       * TODO(cpovirk): As a real solution, remove stock CF's requirement, or change how we use
       * wildcards so that we are compatible with it. This would be a larger project, and it may
       * solve other problems we currently have, enabling us to remove other hacks.
       */
      return false;
    }
    MethodCall containsKeyOrPutCall = (MethodCall) fromNode(containsKeyOrPutNode);

    /*
     * We want to refine the type of any future call to `map.get(key)`. To do so, we need to create
     * a MethodCall with the appropriate values -- in particular, with the appropriate
     * ExecutableElement for the `map.get` call. The appropriate ExecutableElement is not
     * necessarily `java.util.Map.get(Object)` itself, since the call may resolve to an override of
     * that method. Which override? There may be a way to figure it out, but we take the brute-force
     * approach of creating a MethodCall for *every* override and refining the type of each one.
     *
     * XXX: It's theoretically possible for an override's return type to be more specific than the
     * return type of Map.get. This seems extremely unlikely in practice, but maybe it will be more
     * likely with other methods to which we apply the same pattern in the future.
     *
     * To address that theoretical concern (and to move some of the complexity out of this method),
     * we could consider an alternative approach: Insert an entry only for java.util.Map.get itself,
     * and make visitMethodInvocation look up that entry whenever the method it visits is an
     * override of Map.get. However, it worries me that we could potentially end up with different
     * entries for Map.get and its override. I *suspect* that we could always take the more specific
     * one, but given that the existing code works, I'm not going to take any risk right now.
     */
    List<ExecutableElement> mapGetAndRemoveAndOverrides = new ArrayList<>();
    mapGetAndRemoveAndOverrides.addAll(methodAndOverrides(mapGetElement, mapType.type));
    mapGetAndRemoveAndOverrides.addAll(methodAndOverrides(mapRemoveElement, mapType.type));

    boolean storeChanged = false;
    for (ExecutableElement mapGetOrRemoveOrOverride : mapGetAndRemoveAndOverrides) {
      MethodCall getOrRemoveCall =
          new MethodCall(
              mapType.mapValueAsDataflowValue.getUnderlyingType(),
              mapGetOrRemoveOrOverride,
              containsKeyOrPutCall.getReceiver(),
              containsKeyOrPutCall.getArguments().subList(0, 1));

      /*
       * TODO(cpovirk): This "@KeyFor Lite" support is surely flawed in various ways. For example,
       * we don't remove information if someone calls remove(key). But I'm probably failing to even
       * think of bigger problems.
       */
      storeChanged |= refine(getOrRemoveCall, mapType.mapValueAsDataflowValue, store);
    }
    return storeChanged;
  }

  private List<ExecutableElement> methodAndOverrides(
      ExecutableElement method, AnnotatedTypeMirror receiver) {
    return getAllDeclaredSupertypes(receiver).stream()
        .flatMap(type -> type.getUnderlyingType().asElement().getEnclosedElements().stream())
        .filter(ExecutableElement.class::isInstance)
        .map(ExecutableElement.class::cast)
        /*
         * TODO(cpovirk): It would be more correct to pass the corresponding `TypeElement type`
         * to Elements.overrides.
         */
        .filter(e -> isOrOverrides(e, method))
        .collect(toList());
  }

  private void refinePathGetFileNameResultIfDirectoryStreamLoop(
      MethodInvocationNode pathGetFileNameNode, TransferResult<CFValue, NullSpecStore> input) {
    if (!javaNioFileDrectoryStream.isPresent()) {
      // Running with j2cl's limited classpath.
      return;
    }

    Tree pathGetFileNameReceiver = pathGetFileNameNode.getTarget().getReceiver().getTree();
    Element pathGetFileNameReceiverElement = elementFromTree(pathGetFileNameReceiver);
    if (pathGetFileNameReceiverElement == null) {
      return;
    }

    for (TreePath path = pathGetFileNameNode.getTreePath();
        path != null;
        path = path.getParentPath()) {
      if (!(path.getLeaf() instanceof EnhancedForLoopTree)) {
        continue;
      }
      EnhancedForLoopTree forLoop = (EnhancedForLoopTree) path.getLeaf();

      // Is the foreach over a DirectoryStream?
      if (!isErasedSubtype(typeOf(forLoop.getExpression()), javaNioFileDrectoryStream.get())) {
        continue;
      }

      // Is the receiver of path.getFileName(...) the variable from the foreach?
      VariableElement forVariableElement = elementFromDeclaration(forLoop.getVariable());
      if (pathGetFileNameReceiverElement != forVariableElement) {
        continue;
      }

      input.setResultValue(
          analysis.createSingleAnnotationValue(
              minusNull, input.getResultValue().getUnderlyingType()));
    }
  }

  private void refineMapGetResultIfKeySetLoop(
      MethodInvocationNode mapGetNode, TransferResult<CFValue, NullSpecStore> input) {
    Tree mapGetReceiver = mapGetNode.getTarget().getReceiver().getTree();
    if (!(mapGetReceiver instanceof ExpressionTree)) {
      /*
       * TODO(cpovirk): Handle the case of a null mapGetReceiver (probably ImplicitThisNode).
       * Handling that case will also require changing the code below that assumes a member select.
       */
      return;
    }
    ExpressionTree mapGetReceiverExpression = (ExpressionTree) mapGetReceiver;
    Element mapGetArgElement = elementFromTree(mapGetNode.getArgument(0).getTree());
    MapType mapType = new MapType(mapGetReceiver);
    if (mapType.mapValueAsDataflowValue == null) {
      /*
       * Give up. See the comment in refineFutureMapGetFromMapContainsKey. Note that this current
       * method, unlike refineFutureMapGetFromMapContainsKey, does not *crash* if we use the null
       * value. Still, setting the TransferResult value to null seems like a bad idea, so let's not
       * do that.
       */
      return;
    }

    /*
     * TODO(cpovirk): Benchmark the cost of looking the entire way up the tree path. Maybe we should
     * stop at the first foreach or even the first block of any kind?
     *
     * If performance is bad enough, consider this an additional reason to move this code out of
     * NullSpecStore and into our TreeAnnotator, as discussed in visitFieldAccess: TreeAnnotator
     * could make a single pass over the file to infer the return types of all calls to
     * map.get(...), path.getFileName(), and any other APIs en masse.
     */
    for (TreePath path = mapGetNode.getTreePath(); path != null; path = path.getParentPath()) {
      if (!(path.getLeaf() instanceof EnhancedForLoopTree)) {
        continue;
      }
      EnhancedForLoopTree forLoop = (EnhancedForLoopTree) path.getLeaf();

      ExpressionTree forExpression = forLoop.getExpression();
      if (!(forExpression instanceof MethodInvocationTree)) {
        continue;
      }
      MethodInvocationTree forExpressionAsInvocation = (MethodInvocationTree) forExpression;
      ExpressionTree forExpressionSelect = forExpressionAsInvocation.getMethodSelect();
      if (!(forExpressionSelect instanceof MemberSelectTree)) {
        continue;
      }
      ExpressionTree forExpressionReceiver =
          ((MemberSelectTree) forExpressionSelect).getExpression();

      // Is the foreach over something.keySet()?
      ExecutableElement forExpressionElement = elementFromUse(forExpressionAsInvocation);
      if (!isOrOverridesAnyOf(
          forExpressionElement,
          mapKeySetElement,
          navigableMapNavigableKeySetElement,
          navigableMapDescendingKeySetElement)) {
        continue;
      }

      // Is the arg to map.get(...) the variable from the foreach?
      VariableElement forVariableElement = elementFromDeclaration(forLoop.getVariable());
      if (mapGetArgElement != forVariableElement) {
        continue;
      }

      // Is the receiver of map.get(...) the receiver of the foreach's something.keySet()?
      if (!JavaExpression.fromTree(mapGetReceiverExpression)
          .equals(JavaExpression.fromTree(forExpressionReceiver))) {
        continue;
      }

      input.setResultValue(mapType.mapValueAsDataflowValue);
    }
  }

  private final class MapType {
    final AnnotatedTypeMirror type;
    final CFValue mapValueAsDataflowValue;

    MapType(Tree receiverTree) {
      type = atypeFactory.getAnnotatedType(receiverTree);
      AnnotatedDeclaredType typeAsMap = asSuper(atypeFactory, type, javaUtilMap);
      AnnotatedTypeMirror mapValueType = typeAsMap.getTypeArguments().get(1);
      mapValueAsDataflowValue =
          analysis.createAbstractValue(
              mapValueType.getAnnotations(), mapValueType.getUnderlyingType());
    }
  }

  private boolean isGetCanonicalNameOnClassLiteral(MethodInvocationNode node) {
    ExecutableElement method = node.getTarget().getMethod();
    if (!nameMatches(method, "Class", "getCanonicalName")) {
      return false;
    }
    return isOnClassLiteral(node);
  }

  private boolean isGetClassLoaderClassLiteral(MethodInvocationNode node) {
    ExecutableElement method = node.getTarget().getMethod();
    if (!nameMatches(method, "Class", "getClassLoader")) {
      return false;
    }
    return isOnClassLiteral(node);
  }

  private boolean isOnClassLiteral(MethodInvocationNode node) {
    Node receiver = node.getTarget().getReceiver();
    if (!(receiver instanceof FieldAccessNode)) {
      return false;
    }
    FieldAccessNode fieldAccess = (FieldAccessNode) receiver;
    return fieldAccess.getFieldName().equals("class");
  }

  private boolean isGetClassLoaderClassOnThisGetClass(MethodInvocationNode node) {
    ExecutableElement method = node.getTarget().getMethod();
    if (!nameMatches(method, "Class", "getClassLoader")) {
      return false;
    }
    Node receiver = node.getTarget().getReceiver();
    if (!(receiver instanceof MethodInvocationNode)) {
      return false;
    }
    MethodInvocationNode invocation = (MethodInvocationNode) receiver;
    if (!nameMatches(invocation.getTarget().getMethod(), "Object", "getClass")) {
      return false;
    }
    Node invocationTargetReceiver = invocation.getTarget().getReceiver();
    return invocationTargetReceiver instanceof ExplicitThisNode
        || invocationTargetReceiver instanceof ImplicitThisNode;
  }

  private boolean isGetThreadGroupOnCurrentThread(MethodInvocationNode node) {
    ExecutableElement method = node.getTarget().getMethod();
    if (!nameMatches(method, "Thread", "getThreadGroup")) {
      return false;
    }
    Node receiver = node.getTarget().getReceiver();
    if (!(receiver instanceof MethodInvocationNode)) {
      return false;
    }
    MethodInvocationNode invocation = (MethodInvocationNode) receiver;
    if (!nameMatches(invocation.getTarget().getMethod(), "Thread", "currentThread")) {
      return false;
    }
    return true;
  }

  private boolean isGetSuperclassOnGetClass(MethodInvocationNode node) {
    ExecutableElement method = node.getTarget().getMethod();
    if (!nameMatches(method, "Class", "getSuperclass")
        && !nameMatches(method, "Class", "getGenericSuperclass")) {
      return false;
    }
    Node receiver = node.getTarget().getReceiver();
    if (!(receiver instanceof MethodInvocationNode)) {
      return false;
    }
    MethodInvocationNode invocation = (MethodInvocationNode) receiver;
    if (!nameMatches(invocation.getTarget().getMethod(), "Object", "getClass")) {
      return false;
    }
    return true;
  }

  private boolean isReflectiveRead(MethodInvocationNode node) {
    ExecutableElement method = node.getTarget().getMethod();
    return nameMatches(method, "Field", "get") || nameMatches(method, "Method", "invoke");
  }

  private AnnotatedTypeMirror typeWithTopLevelAnnotationsOnly(
      TransferInput<CFValue, NullSpecStore> input, Node node) {
    Set<AnnotationMirror> annotations = input.getValueOfSubNode(node).getAnnotations();
    AnnotatedTypeMirror type = createType(node.getType(), atypeFactory, /*isDeclaration=*/ false);
    type.addAnnotations(annotations);
    return type;
  }

  @Override
  public TransferResult<CFValue, NullSpecStore> visitTypeCast(
      TypeCastNode node, TransferInput<CFValue, NullSpecStore> input) {
    TransferResult<CFValue, NullSpecStore> result = super.visitTypeCast(node, input);
    if (node.getOperand() instanceof MethodInvocationNode) {
      if (nameMatches(
          ((MethodInvocationNode) node.getOperand()).getTarget().getMethod(),
          "Class",
          "newInstance")) {
        setResultValueToNonNull(result);
      }
    }
    return result;
  }

  @Override
  public TransferResult<CFValue, NullSpecStore> visitInstanceOf(
      InstanceOfNode node, TransferInput<CFValue, NullSpecStore> input) {
    CFValue resultValue = super.visitInstanceOf(node, input).getResultValue();
    NullSpecStore thenStore = input.getThenStore();
    NullSpecStore elseStore = input.getElseStore();
    boolean storeChanged = refineNonNull(node.getOperand(), thenStore);
    return new ConditionalTransferResult<>(resultValue, thenStore, elseStore, storeChanged);
  }

  @Override
  public TransferResult<CFValue, NullSpecStore> visitEqualTo(
      EqualToNode node, TransferInput<CFValue, NullSpecStore> input) {
    CFValue resultValue = super.visitEqualTo(node, input).getResultValue();
    NullSpecStore thenStore = input.getThenStore();
    NullSpecStore elseStore = input.getElseStore();
    boolean storeChanged = storeNonNullAndNullIfComparesToNull(node, elseStore, thenStore);
    return new ConditionalTransferResult<>(resultValue, thenStore, elseStore, storeChanged);
  }

  @Override
  public TransferResult<CFValue, NullSpecStore> visitNotEqual(
      NotEqualNode node, TransferInput<CFValue, NullSpecStore> input) {
    CFValue resultValue = super.visitNotEqual(node, input).getResultValue();
    NullSpecStore thenStore = input.getThenStore();
    NullSpecStore elseStore = input.getElseStore();
    boolean storeChanged = storeNonNullAndNullIfComparesToNull(node, thenStore, elseStore);
    return new ConditionalTransferResult<>(resultValue, thenStore, elseStore, storeChanged);
  }

  /**
   * If one operand is a null literal, marks the other as non-null, and returns whether this is a
   * change in its value.
   */
  private boolean storeNonNullIfComparesToNull(BinaryOperationNode node, NullSpecStore store) {
    if (isNullLiteral(node.getLeftOperand())) {
      return refineNonNull(node.getRightOperand(), store);
    } else if (isNullLiteral(node.getRightOperand())) {
      return refineNonNull(node.getLeftOperand(), store);
    }
    return false;
  }

  /**
   * If one operand is a null literal, marks the other as non-null and null in the respective
   * stores, and returns whether this is a change in its value.
   */
  private boolean storeNonNullAndNullIfComparesToNull(
      BinaryOperationNode node, NullSpecStore storeForNonNull, NullSpecStore storeForNull) {
    boolean storeChanged = false;
    if (isNullLiteral(node.getLeftOperand())) {
      storeChanged |= refineNonNull(node.getRightOperand(), storeForNonNull);
      storeChanged |= overwriteWithUnionNull(node.getRightOperand(), storeForNull);
    } else if (isNullLiteral(node.getRightOperand())) {
      storeChanged |= refineNonNull(node.getLeftOperand(), storeForNonNull);
      storeChanged |= overwriteWithUnionNull(node.getLeftOperand(), storeForNull);
    }
    return storeChanged;
  }

  /** Marks the node as non-null, and returns whether this is a change in its value. */
  private boolean refineNonNull(Node node, NullSpecStore store) {
    return refineNonNull(expressionToStoreFor(node), store);
  }

  /** Marks the expression as non-null, and returns whether this is a change in its value. */
  private boolean refineNonNull(JavaExpression expression, NullSpecStore store) {
    return refine(expression, minusNull, store);
  }

  /**
   * Refines the expression to be at least as specific as the target type, and returns whether this
   * is a change in its value.
   */
  private boolean refine(JavaExpression expression, AnnotationMirror target, NullSpecStore store) {
    return refiner.update(expression, target, store);
  }

  /**
   * Refines the expression to be at least as specific as the target type, and returns whether this
   * is a change in its value.
   */
  private boolean refine(JavaExpression expression, CFValue target, NullSpecStore store) {
    return refiner.update(expression, target, store);
  }

  /**
   * Marks the node as unionNull (even if it was previously a more specific type), and returns
   * whether this is a change in its value.
   */
  private boolean overwriteWithUnionNull(Node node, NullSpecStore store) {
    return overwriteWithUnionNull(expressionToStoreFor(node), store);
  }

  /**
   * Marks the expression as unionNull (even if it was previously a more specific type), and returns
   * whether this is a change in its value.
   */
  private boolean overwriteWithUnionNull(JavaExpression expression, NullSpecStore store) {
    return overwrite(expression, unionNull, store);
  }

  /**
   * Marks the expression to be the given target type (even if it was previously a more specific
   * type), and returns whether this is a change in its value.
   */
  private boolean overwrite(
      JavaExpression expression, AnnotationMirror target, NullSpecStore store) {
    return overwriter.update(expression, target, store);
  }

  /**
   * Marks the expression to be the given target type (even if it was previously a more specific
   * type), and returns whether this is a change in its value.
   */
  private boolean overwrite(JavaExpression expression, CFValue target, NullSpecStore store) {
    return overwriter.update(expression, target, store);
  }

  private abstract class Updater {
    abstract boolean shouldNotChangeFromOldToTarget(CFValue old, CFValue target);

    /**
     * Updates the expression's value to match the given target, if permitted by {@link
     * #shouldNotChangeFromOldToTarget}, and returns whether this is a change in its value.
     */
    final boolean update(JavaExpression expression, AnnotationMirror target, NullSpecStore store) {
      return update(
          expression, analysis.createSingleAnnotationValue(target, expression.getType()), store);
    }

    /**
     * Updates the expression's value to match the given target, if permitted by {@link
     * #shouldNotChangeFromOldToTarget} and returns whether this is a change in its value.
     */
    final boolean update(JavaExpression expression, CFValue target, NullSpecStore store) {
      if (!canInsertJavaExpression(expression)) {
        /*
         * Example: In `requireNonNull((SomeType) x)`, `(SomeType) x` appears as Unknown.
         *
         * TODO(cpovirk): Unwrap casts and refine the expression that is being cast. (That probably
         * will not fully eliminate the need for this check, though.)
         */
        return false;
      }
      CFValue old = store.getValue(expression);
      if (shouldNotChangeFromOldToTarget(old, target)) {
        return false;
      }
      /*
       * We call replaceValue instead of insertValue for the case in which we want to overwrite the
       * existing value, rather than just refine it. (In the refining case, we've already performed
       * our own check of whether an update is necessary, so we still won't overwrite an existing
       * value that is more specific than the target.)
       *
       * By performing our own check, we probably also avoid some of the problems caused by our
       * unusual rules. However, it's also conceivable that we introduce bugs, especially when
       * mixing types (like assigning an instance of T and then an instance of Object to the same
       * variable). It would likely be best for our check above to use an existing CF method whose
       * behavior we've tweaked by overriding some other method. See the discussion in
       * valueIsAtLeastAsSpecificAs.
       */
      store.replaceValue(expression, target);
      return true;
    }
  }

  @Override
  public CFValue moreSpecificValue(CFValue value1, CFValue value2) {
    // See the discussion in valueIsAtLeastAsSpecificAs.
    if (value2 == null) {
      return value1;
    }
    return valueIsAtLeastAsSpecificAs(value1, value2) ? value1 : value2;
  }

  private final Updater refiner =
      new Updater() {
        @Override
        boolean shouldNotChangeFromOldToTarget(CFValue old, CFValue target) {
          return valueIsAtLeastAsSpecificAs(old, target);
        }
      };
  private final Updater overwriter =
      new Updater() {
        @Override
        boolean shouldNotChangeFromOldToTarget(CFValue old, CFValue target) {
          return target.equals(old);
        }
      };

  /*
   * TODO(cpovirk): Instead of calling into this special method in particular cases, should we
   * instead edit CFValue.mostSpecific and/or CFAnalysis.something to have the behavior we want in
   * general? That might avoid any problems like those hypothesized in Updater.update.
   *
   * In particular, I fear that the CF code doesn't handle our unusual difference between NonNull
   * ("project to NonNull") and the other annotations ("most general wins") the way that we'd want.
   *
   * (Maybe this is yet another thing that would get better if we fit our substitution/bounds rules
   * into CF's normal model.)
   */
  private boolean valueIsAtLeastAsSpecificAs(CFValue value, CFValue targetDataflowValue) {
    if (value == null) {
      return false;
    }
    AnnotationMirror existing =
        atypeFactory
            .getQualifierHierarchy()
            .findAnnotationInHierarchy(value.getAnnotations(), unionNull);
    AnnotationMirror target =
        atypeFactory
            .getQualifierHierarchy()
            .findAnnotationInHierarchy(targetDataflowValue.getAnnotations(), unionNull);
    if (existing != null && areSame(existing, minusNull)) {
      return true;
    }
    if (target != null && areSame(target, minusNull)) {
      return false;
    }
    if (existing == null) {
      return true;
    }
    if (target == null) {
      return false;
    }
    return atypeFactory.getQualifierHierarchy().greatestLowerBound(existing, target) == existing;
  }

  private static boolean isNullLiteral(Node node) {
    return node.getTree().getKind() == NULL_LITERAL;
  }

  // TODO(cpovirk): Maybe avoid mutating the result value in place?

  private void setResultValueToNonNull(TransferResult<CFValue, NullSpecStore> result) {
    setResultValue(result, minusNull);
  }

  private void setResultValueOperatorToUnspecified(TransferResult<CFValue, NullSpecStore> result) {
    setResultValue(result, nullnessOperatorUnspecified);
  }

  private void setResultValue(
      TransferResult<CFValue, NullSpecStore> result, AnnotationMirror qual) {
    /*
     * TODO(cpovirk): Refine the result, rather than overwrite it. (And rename the method
     * accordingly.)
     *
     * That is, if the result is already @NonNull, don't weaken it to @NullnessUnspecified.
     *
     * The reason: The existing result value comes from super.visit*, which may reflect the result
     * of a null check from earlier in the method. Granted, it is extremely unlikely that there will
     * have been such a null check in the case of the specific methods we're using
     * setResultValueOperatorToUnspecified for: Users are unlikely to write code like:
     *
     * if (clazz.cast(foo) != null) { return class.cast(foo); }
     */
    result.setResultValue(
        analysis.createAbstractValue(singleton(qual), result.getResultValue().getUnderlyingType()));
  }

  private JavaExpression expressionToStoreFor(Node node) {
    while (node instanceof AssignmentNode) {
      // XXX: If there are multiple levels of assignment, we could replaceValue for *every* target.
      node = ((AssignmentNode) node).getTarget();
    }
    return fromNode(node);
  }

  private boolean isOrOverrides(
      ExecutableElement overrider, Optional<ExecutableElement> overridden) {
    // `overridden` can be absent if we're running with j2cl's limited classpath.
    return overridden.isPresent() && isOrOverrides(overrider, overridden.get());
  }

  private boolean isOrOverrides(ExecutableElement overrider, ExecutableElement overridden) {
    return overrider.equals(overridden)
        || atypeFactory
            .getElementUtils()
            .overrides(overrider, overridden, (TypeElement) overrider.getEnclosingElement());
  }

  private boolean isOrOverridesAnyOf(
      ExecutableElement overrider, ExecutableElement a, ExecutableElement b, ExecutableElement c) {
    return isOrOverrides(overrider, a)
        || isOrOverrides(overrider, b)
        || isOrOverrides(overrider, c);
  }

  private boolean isErasedSubtype(TypeMirror sub, TypeMirror sup) {
    Types types = atypeFactory.types;
    return types.isSubtype(types.erasure(sub), types.erasure(sup));
  }

  /**
   * Returns all declared supertypes of the given type, including the type itself and any transitive
   * supertypes. The returned list may contain duplicates.
   */
  private static List<AnnotatedDeclaredType> getAllDeclaredSupertypes(AnnotatedTypeMirror type) {
    List<AnnotatedDeclaredType> result = new ArrayList<>();
    collectAllDeclaredSupertypes(type, result);
    return result;
  }

  private static void collectAllDeclaredSupertypes(
      AnnotatedTypeMirror type, List<AnnotatedDeclaredType> result) {
    if (type instanceof AnnotatedDeclaredType) {
      result.add((AnnotatedDeclaredType) type);
    }
    for (AnnotatedTypeMirror supertype : type.directSupertypes()) {
      collectAllDeclaredSupertypes(supertype, result);
    }
  }

  private static final Set<String> ALWAYS_PRESENT_PROPERTY_VALUES =
      unmodifiableSet(
          new LinkedHashSet<>(
              asList(
                  "java.version",
                  "java.vendor",
                  "java.vendor.url",
                  "java.home",
                  "java.vm.specification.version",
                  "java.vm.specification.vendor",
                  "java.vm.specification.name",
                  "java.vm.version",
                  "java.vm.vendor",
                  "java.vm.name",
                  "java.specification.version",
                  "java.specification.vendor",
                  "java.specification.name",
                  "java.class.version",
                  "java.class.path",
                  "java.library.path",
                  "java.io.tmpdir",
                  // Omit "java.compiler": It is sometimes absent in practice.
                  "os.name",
                  "os.arch",
                  "os.version",
                  "file.separator",
                  "path.separator",
                  "line.separator",
                  "user.name",
                  "user.home",
                  "user.dir")));
}
