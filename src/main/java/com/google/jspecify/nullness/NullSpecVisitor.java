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

import static com.sun.source.tree.Tree.Kind.ARRAY_TYPE;
import static com.sun.source.tree.Tree.Kind.EXTENDS_WILDCARD;
import static com.sun.source.tree.Tree.Kind.PRIMITIVE_TYPE;
import static com.sun.source.tree.Tree.Kind.SUPER_WILDCARD;
import static com.sun.source.tree.Tree.Kind.UNBOUNDED_WILDCARD;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static javax.lang.model.element.ElementKind.ENUM_CONSTANT;
import static javax.lang.model.element.ElementKind.PACKAGE;
import static javax.lang.model.type.TypeKind.DECLARED;
import static javax.tools.Diagnostic.Kind.ERROR;
import static org.checkerframework.javacutil.AnnotationUtils.areSameByName;
import static org.checkerframework.javacutil.TreeUtils.annotationsFromTypeAnnotationTrees;
import static org.checkerframework.javacutil.TreeUtils.elementFromDeclaration;
import static org.checkerframework.javacutil.TreeUtils.elementFromTree;
import static org.checkerframework.javacutil.TreeUtils.elementFromUse;
import static org.checkerframework.javacutil.TypesUtils.isPrimitive;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.AssertTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeValidator;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.common.basetype.TypeValidator;
import org.checkerframework.framework.source.DiagMessage;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NullnessUnspecified;

public final class NullSpecVisitor extends BaseTypeVisitor<NullSpecAnnotatedTypeFactory> {
  private final AnnotationMirror nullable;
  private final AnnotationMirror nullnessUnspecified;
  private final boolean checkImpl;

  public NullSpecVisitor(BaseTypeChecker checker) {
    super(checker);
    nullable = AnnotationBuilder.fromClass(elements, Nullable.class);
    nullnessUnspecified = AnnotationBuilder.fromClass(elements, NullnessUnspecified.class);
    checkImpl = checker.hasOption("checkImpl");
  }

  private void ensureNonNull(Tree tree) {
    ensureNonNull(tree, /*messageKey=*/ "dereference");
  }

  private void ensureNonNull(Tree tree, String messageKey) {
    AnnotatedTypeMirror type = atypeFactory.getAnnotatedType(tree);
    // Maybe this should call isSubtype(type, nonNullObject)? I'd need to create nonNullObject.
    if (!isPrimitive(type.getUnderlyingType())
        && !atypeFactory.isNullExclusiveUnderEveryParameterization(type)) {
      checker.reportError(tree, messageKey, type);
    }
  }

  public Void visitBlock(BlockTree node, Void p) {
    if (checkImpl) {
      return super.visitBlock(node, p);
    } else {
      // TODO(cpovirk): Should we still check any classes inside the block (e.g., anonymous)?
      return null;
    }
  }

  @Override
  protected void checkConstructorResult(
      AnnotatedExecutableType constructorType, ExecutableElement constructorElement) {
    // TODO: ensure no explicit annotations on class & constructor
  }

  @Override
  protected boolean skipReceiverSubtypeCheck(
      MethodInvocationTree node,
      AnnotatedTypeMirror methodDefinitionReceiver,
      AnnotatedTypeMirror methodCallReceiver) {
    /*
     * Skip the check that requires receivers to be null-exclusive. I *believe* this is redundant
     * with the check in visitMemberSelect. And the check in visitMemberSelect both covers more
     * cases (including field lookups) and provides a more tailored error message.
     */
    return true;
  }

  @Override
  public Void visitMemberSelect(MemberSelectTree node, Void p) {
    Element element = elementFromTree(node);
    if (element != null
        && !element.getKind().isClass()
        && !element.getKind().isInterface()
        && element.getKind() != PACKAGE
        && !node.getIdentifier().contentEquals("class")) {
      ensureNonNull(node.getExpression());
      /*
       * By contrast, if it's a class/interface, the select must be on a type, like `Foo.Baz` or
       * `Foo<Bar>.Baz`, or it must be a fully qualified type name, like `java.util.List`. In either
       * case, we don't need to check that the "expression" is non-null because the code is not
       * actually dereferencing anything.
       *
       * In fact, a check that the type is non-null is currently not *safe*: The "expression" tree
       * appears to default to NullnessUnspecified in non-null-aware code.
       *
       * Note that our defaulting of enclosing types in
       * writeDefaultsForIntrinsicallyNonNullableComponents does not help. It does not help even
       * when I retrieve the type of the entire MemberSelectTree (and then pull out the outer type
       * from that).
       *
       * The code path that we end up in appears to be looking specifically at the class referenced
       * by the MemberSelectTree, without regard to any annotations on, e.g., the VariableTree that
       * it is the type for. We end up in AnnotatedTypeFactory.fromElement. Possibly that's bogus:
       * Not every MemberSelectTree is an "expression" in the usual sense. Perhaps it's our job not
       * to call getAnnotatedType on such trees? So let's not.
       */
    }
    return super.visitMemberSelect(node, p);
  }

  @Override
  public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void p) {
    ensureNonNull(node.getExpression());
    return super.visitEnhancedForLoop(node, p);
  }

  @Override
  public Void visitArrayAccess(ArrayAccessTree node, Void p) {
    ensureNonNull(node.getExpression());
    return super.visitArrayAccess(node, p);
  }

  @Override
  protected void checkThrownExpression(ThrowTree node) {
    ensureNonNull(node.getExpression());
  }

  @Override
  public Void visitSynchronized(SynchronizedTree node, Void p) {
    ensureNonNull(node.getExpression());
    return super.visitSynchronized(node, p);
  }

  @Override
  public Void visitAssert(AssertTree node, Void p) {
    ensureNonNull(node.getCondition());
    if (node.getDetail() != null) {
      ensureNonNull(node.getDetail());
    }
    return super.visitAssert(node, p);
  }

  @Override
  public Void visitIf(IfTree node, Void p) {
    ensureNonNull(node.getCondition());
    return super.visitIf(node, p);
  }

  // TODO: binary, unary, compoundassign, typecast, ...

  @Override
  public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
    if (isCheckNotNull(node)) {
      ensureNonNull(node.getArguments().get(0), /*messageKey=*/ "checknotnull");
      /*
       * We don't return here: We still want to descend into arguments, as they may themselves
       * contain method calls, etc. that we want to check.
       *
       * This means that the supertype will report *another* error for this call -- two errors, in
       * fact. But at least we get to get in first and present a better message.
       *
       * If we prefer, we could instead return here -- but only if ensureNonNull reported an error!
       * In the error case, that could hide additional errors related to subexpressions, but it
       * would eliminate the warning spam from the superclass.
       *
       * TODO(cpovirk): Or maybe we should override the annotations on checkNotNull internally? Then
       * the superclass wouldn't report an error, *and* we could still check subexpressions.
       */
    }
    return super.visitMethodInvocation(node, p);
  }

  private boolean isCheckNotNull(MethodInvocationTree node) {
    ExecutableElement method = elementFromUse(node);
    if (method == null) {
      return false;
    }
    if (!method.getSimpleName().contentEquals("checkNotNull")) {
      return false;
    }
    if (!method.getEnclosingElement().getSimpleName().contentEquals("Preconditions")) {
      // TODO(cpovirk): Ensure that it's com.google.common.base.Preconditions specifically.
      return false;
    }
    return true;
  }

  @Override
  public Void visitNewClass(NewClassTree node, Void p) {
    ExecutableElement element = elementFromUse(node);
    if (element.getSimpleName().contentEquals("<init>")
        && element.getEnclosingElement().getSimpleName().contentEquals("AtomicReference")
        && element.getParameters().isEmpty()) {
      // TODO(cpovirk): Ensure that it's java.util.concurrent.atomic.AtomicReference specifically.
      // TODO(cpovirk): Handle super() calls. And does this handle anonymous classes right?
      AnnotatedTypeMirror typeArg = atypeFactory.getAnnotatedType(node).getTypeArguments().get(0);
      if (!atypeFactory.isNullInclusiveUnderEveryParameterization(typeArg)) {
        checker.reportError(node, "atomicreference.must.include.null", typeArg);
      }
    }
    return super.visitNewClass(node, p);
  }

  /*
   * We report some errors of the form "X should not be annotated" in isTopLevelValidType. We do
   * that because visitAnnotatedType is not invoked in many cases we might like for it to be
   * invoked. For example, it doesn't run on an annotated return type. That's because the visit*
   * methods are triggered based on javac tree structure, and the return type's annotations get
   * attached to the *method* tree.
   *
   * Still, visitTypeParameter and visitAnnotatedType are the best places I have found for at least
   * few specific checks. That's because, for those checks especially, we probably want to operate
   * on source trees, rather than on derived types. The advantages of operating on source trees are:
   *
   * - If we instead want to look for annotations on a type parameter or wildcard based on the
   * derived types, we need to ask CF questions like "What is the lower/upper bound?" since that is
   * what CF translates such annotations into. That then requires us to carefully distinguish
   * between implicit upper bounds (like the upper bound of `? super Foo`) and explicit upper bounds
   * (like the upper bound of `@Nullable ? super Foo`). This is likely to be clumsy at best,
   * requiring us to effectively look at information in the source code, anyway -- if sufficient
   * information is even available, especially across compilation boundaries!
   *
   * - IIUC, the visit* methods run only on source code that is compiled by CF. By implementing
   * those methods, we ensure that we don't report problems in our dependencies. (Or might we be
   * able to avoid that by checking isDeclaration()?)
   *
   * - We might also like that the visit* methods can check specifically for the JSpecify
   * annotations. This means that people can alias annotations like CF's own @Nullable to ours, and
   * this checker won't produce errors if they're using in non-JSpecify-recognized locations. (On
   * the other hand, some users might *want* us to produce warnings in such cases so that they are
   * informed that they're stepping outside of core JSpecify semantics.)
   */

  @Override
  public Void visitTypeParameter(TypeParameterTree node, Void p) {
    checkNoNullnessAnnotations(node, node.getAnnotations(), "type.parameter.annotated");
    return super.visitTypeParameter(node, p);
  }

  @Override
  public Void visitAnnotatedType(AnnotatedTypeTree node, Void p) {
    Kind kind = node.getUnderlyingType().getKind();
    if (kind == UNBOUNDED_WILDCARD || kind == EXTENDS_WILDCARD || kind == SUPER_WILDCARD) {
      checkNoNullnessAnnotations(node, node.getAnnotations(), "wildcard.annotated");
    } else if (kind == PRIMITIVE_TYPE) {
      checkNoNullnessAnnotations(node, node.getAnnotations(), "primitive.annotated");
    }
    return super.visitAnnotatedType(node, p);
  }

  @Override
  public Void visitVariable(VariableTree node, Void p) {
    if (isPrimitiveOrArrayOfPrimitive(node.getType())) {
      checkNoNullnessAnnotations(node, node.getModifiers().getAnnotations(), "primitive.annotated");
    }

    VariableElement element = elementFromDeclaration(node);
    if (element.getKind() == ENUM_CONSTANT) {
      checkNoNullnessAnnotations(
          node, node.getModifiers().getAnnotations(), "enum.constant.annotated");
    }
    return super.visitVariable(node, p);
  }

  @Override
  public Void visitMethod(MethodTree node, Void p) {
    if (node.getReturnType() != null && isPrimitiveOrArrayOfPrimitive(node.getReturnType())) {
      checkNoNullnessAnnotations(node, node.getModifiers().getAnnotations(), "primitive.annotated");
    }
    return super.visitMethod(node, p);
  }

  // TODO(cpovirk): Are there any more visit* methods that might run for annotated primitives?

  private boolean isPrimitiveOrArrayOfPrimitive(Tree type) {
    return type.getKind() == PRIMITIVE_TYPE
        || (type.getKind() == ARRAY_TYPE
            && ((ArrayTypeTree) type).getType().getKind() == PRIMITIVE_TYPE);
  }

  private void checkNoNullnessAnnotations(
      Tree node, List<? extends AnnotationTree> annotations, String messageKey) {
    for (AnnotationMirror annotation : annotationsFromTypeAnnotationTrees(annotations)) {
      // TODO(cpovirk): Check for aliases here (and perhaps elsewhere).
      if (areSameByName(annotation, nullable) || areSameByName(annotation, nullnessUnspecified)) {
        checker.reportError(node, messageKey);
      }
    }
  }

  @Override
  protected boolean checkMethodReferenceAsOverride(MemberReferenceTree node, Void p) {
    /*
     * Class.cast accepts `Object?`, so there's no need to check its parameter type: It can accept
     * nullable and non-nullable values alike.
     *
     * It returns `T?`, so we normally do need to check its _return type_ to see if that fits the
     * required type. *But*, if its parameter type is non-nullable, then so too is its return type.
     * And if its return type is non-nullable, then it returns a value that works whether we need a
     * nullable or non-nullable value. In that case, we can skip the superclass's checks entirely.
     *
     * This all relies on the fact that CF can infer the return type as non-nullable in the first
     * place. I had expected that to work in simple cases but fail in more complex ones. But I
     * haven't seen if fail yet. If it does fail someday, we can probably patch some common cases up
     * in our TreeAnnotator by making visitMethodInvocation check Stream.map calls to see if they
     * fit the form filter(...).map(Foo.class::cast), where the filter is isInstance, x != null,
     * etc. In that case, we can modify the Stream<...> return type to have a non-nullable element
     * type. I hope.
     */
    return isClassCastAppliedToNonNullableType(node)
        || super.checkMethodReferenceAsOverride(node, p);
  }

  private boolean isClassCastAppliedToNonNullableType(MemberReferenceTree node) {
    // TODO(cpovirk): Ensure that it's java.lang.Class.cast specifically.
    if (!node.getName().contentEquals("cast")) {
      return false;
    }
    AnnotatedExecutableType functionType = atypeFactory.getFunctionTypeFromTree(node);
    AnnotatedTypeMirror parameterType = functionType.getParameterTypes().get(0);
    return atypeFactory.isNullExclusiveUnderEveryParameterization(parameterType);
  }

  @Override
  protected TypeValidator createTypeValidator() {
    return new NullSpecTypeValidator(checker, this, atypeFactory);
  }

  private static final class NullSpecTypeValidator extends BaseTypeValidator {
    NullSpecTypeValidator(
        BaseTypeChecker checker, BaseTypeVisitor<?> visitor, AnnotatedTypeFactory atypeFactory) {
      super(checker, visitor, atypeFactory);
    }

    @Override
    protected List<DiagMessage> isTopLevelValidType(
        QualifierHierarchy qualifierHierarchy, AnnotatedTypeMirror type) {
      /*
       * This method is where we report some errors of the form "X should not be annotated." But
       * note that we report some other errors of that form in NullSpecVisitor methods like
       * visitAnnotatedType.
       *
       * TODO(cpovirk): It might actually make more sense to report *all* such errors in
       * NullSpecVisitor.visit* methods, especially to ensure that we don't report errors for
       * declarations that appear in library dependencies. However, those methods make it trickier
       * to ensure that we visit *all* annotated types, as discussed in a comment on
       * visitAnnotatedType above.
       */
      if (type.getKind() == DECLARED) {
        AnnotatedDeclaredType enclosingType = ((AnnotatedDeclaredType) type).getEnclosingType();
        if (enclosingType != null && hasNullableOrNullnessUnspecified(enclosingType)) {
          return singletonList(new DiagMessage(ERROR, "outer.annotated"));
        }
      }
      return super.isTopLevelValidType(qualifierHierarchy, type);
    }

    boolean hasNullableOrNullnessUnspecified(AnnotatedTypeMirror type) {
      return type.hasAnnotation(Nullable.class) || type.hasAnnotation(NullnessUnspecified.class);
    }
  }

  @Override
  protected Set<? extends AnnotationMirror> getExceptionParameterLowerBoundAnnotations() {
    return new HashSet<>(asList(AnnotationBuilder.fromClass(elements, NonNull.class)));
  }
}
