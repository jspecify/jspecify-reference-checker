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
import static com.sun.source.tree.Tree.Kind.NULL_LITERAL;
import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.Collections.unmodifiableSet;
import static javax.lang.model.element.ElementKind.PACKAGE;
import static org.checkerframework.dataflow.expression.JavaExpression.fromNode;
import static org.checkerframework.framework.type.AnnotatedTypeMirror.createType;
import static org.checkerframework.javacutil.AnnotationUtils.areSame;

import java.util.LinkedHashSet;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import org.checkerframework.dataflow.analysis.ConditionalTransferResult;
import org.checkerframework.dataflow.analysis.TransferInput;
import org.checkerframework.dataflow.analysis.TransferResult;
import org.checkerframework.dataflow.cfg.node.ArrayAccessNode;
import org.checkerframework.dataflow.cfg.node.AssignmentNode;
import org.checkerframework.dataflow.cfg.node.BinaryOperationNode;
import org.checkerframework.dataflow.cfg.node.ClassNameNode;
import org.checkerframework.dataflow.cfg.node.EqualToNode;
import org.checkerframework.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.dataflow.cfg.node.InstanceOfNode;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.NotEqualNode;
import org.checkerframework.dataflow.cfg.node.StringLiteralNode;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.framework.flow.CFAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFTransfer;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NullnessUnspecified;

public final class NullSpecTransfer extends CFTransfer {
  private final NullSpecAnnotatedTypeFactory atypeFactory;
  private final AnnotationMirror nonNull;
  private final AnnotationMirror codeNotNullnessAware;
  private final AnnotationMirror unionNull;

  public NullSpecTransfer(CFAnalysis analysis) {
    super(analysis);
    atypeFactory = (NullSpecAnnotatedTypeFactory) analysis.getTypeFactory();
    nonNull = AnnotationBuilder.fromClass(atypeFactory.getElementUtils(), NonNull.class);
    codeNotNullnessAware =
        AnnotationBuilder.fromClass(atypeFactory.getElementUtils(), NullnessUnspecified.class);
    unionNull = AnnotationBuilder.fromClass(atypeFactory.getElementUtils(), Nullable.class);
  }

  @Override
  public TransferResult<CFValue, CFStore> visitFieldAccess(
      FieldAccessNode node, TransferInput<CFValue, CFStore> input) {
    TransferResult<CFValue, CFStore> result = super.visitFieldAccess(node, input);
    if (node.getFieldName().equals("class")) {
      /*
       * TODO(cpovirk): Would it make more sense to do this in our TreeAnnotator? Alternatively,
       * would it make more sense to move most of our code out of TreeAnnotator and perform the same
       * actions here instead?
       *
       * TreeAnnotator could make more sense if we needed to change types that appear in
       * "non-dataflow" locations -- perhaps if we needed to change the types of a method's
       * parameters or return type before overload checking occurs? But I don't know that we'll need
       * to do that.
       *
       * One case in which we _do_ need TreeAnnotator is when we change the nullness of a
       * _non-top-level_ type. Currently, we do this to change the element type of a Stream when we
       * know that it is non-nullable. (Aside: Another piece of that logic -- well, a somewhat
       * different piece of logic with a similar purpose -- lives in
       * checkMethodReferenceAsOverride. So that logic is already split across files.)
       *
       * A possible downside of TreeAnnotator is that it applies only to constructs whose _source
       * code_ we check. But I'm not sure how much of a problem this is in practice, either: During
       * dataflow checks, we're more interested in the _usages_ of APIs than in their declarations,
       * and the _usages_ appear in source we're checking.
       */
      setResultValueToNonNull(result);
    }
    return result;
  }

  @Override
  public TransferResult<CFValue, CFStore> visitMethodInvocation(
      MethodInvocationNode node, TransferInput<CFValue, CFStore> input) {
    TransferResult<CFValue, CFStore> result = super.visitMethodInvocation(node, input);
    CFStore thenStore = input.getThenStore();
    CFStore elseStore = input.getElseStore();
    ExecutableElement method = node.getTarget().getMethod();

    boolean storeChanged = false;

    if (nameMatches(method, "Objects", "requireNonNull")) {
      // TODO(cpovirk): Ensure that it's java.util.Objects specifically.
      storeChanged |= putNonNull(node.getArgument(0), thenStore, elseStore);
    }

    if (nameMatches(method, "Class", "isInstance")) {
      // TODO(cpovirk): Ensure that it's java.lang.Class specifically.
      storeChanged |= putNonNull(node.getArgument(0), thenStore);
    }

    if (isGetPackageCallOnClassInNamedPackage(node)) {
      setResultValueToNonNull(result);
    }

    if (isGetCanonicalNameOnClassLiteral(node)) {
      setResultValueToNonNull(result);
    }

    // TODO(cpovirk): Ensure that it's com.google.common.base.Preconditions specifically.
    if (nameMatches(method, "Preconditions", "checkState")
        && node.getArgument(0) instanceof NotEqualNode) {
      storeChanged |= putNullCheckResult((NotEqualNode) node.getArgument(0), thenStore, elseStore);
    }

    if (nameMatches(method, "Class", "cast") || nameMatches(method, "Optional", "orElse")) {
      // TODO(cpovirk): Ensure that it's java.lang.Class specifically.
      AnnotatedTypeMirror type = typeWithTopLevelAnnotationsOnly(input, node.getArgument(0));
      if (atypeFactory.withLeastConvenientWorld().isNullExclusiveUnderEveryParameterization(type)) {
        setResultValueToNonNull(result);
      } else if (atypeFactory
          .withMostConvenientWorld()
          .isNullExclusiveUnderEveryParameterization(type)) {
        setResultValueToUnspecified(result);
      }
    } else if (nameMatches(method, "System", "getProperty")) {
      // TODO(cpovirk): Ensure that it's java.lang.System specifically.
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
    }

    return new ConditionalTransferResult<>(
        result.getResultValue(), thenStore, elseStore, storeChanged);
  }

  private boolean isGetPackageCallOnClassInNamedPackage(MethodInvocationNode node) {
    ExecutableElement method = node.getTarget().getMethod();
    if (!nameMatches(method, "Class", "getPackage")) {
      return false;
    }
    Node receiver = node.getTarget().getReceiver();
    if (!(receiver instanceof FieldAccessNode)) {
      return false;
    }
    FieldAccessNode fieldAccess = (FieldAccessNode) receiver;
    if (!fieldAccess.getFieldName().equals("class")) {
      return false;
    }
    ClassNameNode className = (ClassNameNode) fieldAccess.getReceiver();
    return isInPackage(className.getElement());
  }

  private boolean isGetCanonicalNameOnClassLiteral(MethodInvocationNode node) {
    ExecutableElement method = node.getTarget().getMethod();
    if (!nameMatches(method, "Class", "getCanonicalName")) {
      return false;
    }
    Node receiver = node.getTarget().getReceiver();
    if (!(receiver instanceof FieldAccessNode)) {
      return false;
    }
    FieldAccessNode fieldAccess = (FieldAccessNode) receiver;
    return fieldAccess.getFieldName().equals("class");
  }

  private AnnotatedTypeMirror typeWithTopLevelAnnotationsOnly(
      TransferInput<CFValue, CFStore> input, Node node) {
    Set<AnnotationMirror> annotations = input.getValueOfSubNode(node).getAnnotations();
    AnnotatedTypeMirror type = createType(node.getType(), atypeFactory, /*isDeclaration=*/ false);
    type.addAnnotations(annotations);
    return type;
  }

  @Override
  public TransferResult<CFValue, CFStore> visitInstanceOf(
      InstanceOfNode node, TransferInput<CFValue, CFStore> input) {
    CFValue resultValue = super.visitInstanceOf(node, input).getResultValue();
    CFStore thenStore = input.getThenStore();
    CFStore elseStore = input.getElseStore();
    boolean storeChanged = putNonNull(node.getOperand(), thenStore);
    return new ConditionalTransferResult<>(resultValue, thenStore, elseStore, storeChanged);
  }

  @Override
  public TransferResult<CFValue, CFStore> visitEqualTo(
      EqualToNode node, TransferInput<CFValue, CFStore> input) {
    CFValue resultValue = super.visitEqualTo(node, input).getResultValue();
    CFStore thenStore = input.getThenStore();
    CFStore elseStore = input.getElseStore();
    boolean storeChanged = putNullCheckResult(node, elseStore);
    return new ConditionalTransferResult<>(resultValue, thenStore, elseStore, storeChanged);
  }

  @Override
  public TransferResult<CFValue, CFStore> visitNotEqual(
      NotEqualNode node, TransferInput<CFValue, CFStore> input) {
    CFValue resultValue = super.visitNotEqual(node, input).getResultValue();
    CFStore thenStore = input.getThenStore();
    CFStore elseStore = input.getElseStore();
    boolean storeChanged = putNullCheckResult(node, thenStore);
    return new ConditionalTransferResult<>(resultValue, thenStore, elseStore, storeChanged);
  }

  /**
   * If one operand is a null literal, marks the other as non-null, and returns whether this is a
   * change in its value.
   */
  private boolean putNullCheckResult(BinaryOperationNode node, CFStore store) {
    if (isNullLiteral(node.getLeftOperand())) {
      return putNonNull(node.getRightOperand(), store);
    } else if (isNullLiteral(node.getRightOperand())) {
      return putNonNull(node.getLeftOperand(), store);
    }
    return false;
  }

  /**
   * If one operand is a null literal, marks the other as non-null, and returns whether this is a
   * change in its value in either store.
   */
  private boolean putNullCheckResult(BinaryOperationNode node, CFStore store1, CFStore store2) {
    boolean storeChanged = false;
    storeChanged |= putNullCheckResult(node, store1);
    storeChanged |= putNullCheckResult(node, store2);
    return storeChanged;
  }

  /** Marks the node as non-null, and returns whether this is a change in its value. */
  private boolean putNonNull(Node node, CFStore store) {
    boolean storeChanged = false;
    while (node instanceof AssignmentNode) {
      // XXX: If there are multiple levels of assignment, we could insertValue for *every* target.
      node = ((AssignmentNode) node).getTarget();
    }
    if (trustedToRemainNonNull(node)) {
      // allowNonDeterministic=true because we perform our own sort of determinism check.
      JavaExpression receiver = fromNode(atypeFactory, node, /*allowNonDeterministic=*/ true);
      CFValue oldValue = store.getValue(receiver);
      storeChanged = !alreadyKnownToBeNonNull(oldValue);
      store.insertValue(receiver, nonNull);
    }
    return storeChanged;
  }

  /**
   * Marks the node as non-null, and returns whether this is a change in its value in either store.
   */
  private boolean putNonNull(Node node, CFStore store1, CFStore store2) {
    boolean storeChanged = false;
    storeChanged |= putNonNull(node, store1);
    storeChanged |= putNonNull(node, store2);
    return storeChanged;
  }

  private boolean trustedToRemainNonNull(Node node) {
    // TODO(cpovirk): Just trust *all* nodes to remain non-null? We're nearly there, anyway...
    return node instanceof ArrayAccessNode
        || node instanceof FieldAccessNode
        || node instanceof LocalVariableNode
        || node instanceof MethodInvocationNode;
  }

  private boolean alreadyKnownToBeNonNull(CFValue value) {
    if (value == null) {
      return false;
    }
    AnnotationMirror annotation =
        atypeFactory
            .getQualifierHierarchy()
            .findAnnotationInHierarchy(value.getAnnotations(), unionNull);
    return annotation != null && areSame(annotation, nonNull);
  }

  private static boolean isNullLiteral(Node node) {
    return node.getTree().getKind() == NULL_LITERAL;
  }

  // TODO(cpovirk): Maybe avoid mutating the result value in place?

  private void setResultValueToNonNull(TransferResult<CFValue, CFStore> result) {
    setResultValue(result, nonNull);
  }

  private void setResultValueToUnspecified(TransferResult<CFValue, CFStore> result) {
    setResultValue(result, codeNotNullnessAware);
  }

  private void setResultValue(TransferResult<CFValue, CFStore> result, AnnotationMirror qual) {
    result.setResultValue(
        new CFValue(analysis, singleton(qual), result.getResultValue().getUnderlyingType()));
  }

  private static boolean isInPackage(Element element) {
    for (; element != null; element = element.getEnclosingElement()) {
      if (element.getKind() == PACKAGE && !((PackageElement) element).isUnnamed()) {
        return true;
      }
    }
    return false;
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
