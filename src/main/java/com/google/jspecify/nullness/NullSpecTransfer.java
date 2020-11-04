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

import static com.sun.source.tree.Tree.Kind.NULL_LITERAL;
import static org.checkerframework.dataflow.expression.FlowExpressions.internalReprOf;
import static org.checkerframework.javacutil.AnnotationUtils.areSame;

import javax.lang.model.element.AnnotationMirror;
import org.checkerframework.dataflow.analysis.ConditionalTransferResult;
import org.checkerframework.dataflow.analysis.TransferInput;
import org.checkerframework.dataflow.analysis.TransferResult;
import org.checkerframework.dataflow.cfg.node.ArrayAccessNode;
import org.checkerframework.dataflow.cfg.node.BinaryOperationNode;
import org.checkerframework.dataflow.cfg.node.EqualToNode;
import org.checkerframework.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.dataflow.cfg.node.InstanceOfNode;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.NotEqualNode;
import org.checkerframework.dataflow.expression.Receiver;
import org.checkerframework.framework.flow.CFAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFTransfer;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.jspecify.annotations.Nullable;

public final class NullSpecTransfer extends CFTransfer {
  private final NullSpecAnnotatedTypeFactory atypeFactory;
  private final AnnotationMirror nonNull;
  private final AnnotationMirror unionNull;

  public NullSpecTransfer(CFAnalysis analysis) {
    super(analysis);
    atypeFactory = (NullSpecAnnotatedTypeFactory) analysis.getTypeFactory();
    nonNull = AnnotationBuilder.fromClass(atypeFactory.getElementUtils(), NonNull.class);
    unionNull = AnnotationBuilder.fromClass(atypeFactory.getElementUtils(), Nullable.class);
  }

  @Override
  public TransferResult<CFValue, CFStore> visitInstanceOf(
      InstanceOfNode node, TransferInput<CFValue, CFStore> input) {
    CFValue resultValue = super.visitInstanceOf(node, input).getResultValue();
    CFStore thenStore = input.getThenStore();
    CFStore elseStore = input.getElseStore();
    boolean storeChanged = putNonNull(thenStore, node.getOperand());
    return new ConditionalTransferResult<>(resultValue, thenStore, elseStore, storeChanged);
  }

  @Override
  public TransferResult<CFValue, CFStore> visitEqualTo(
      EqualToNode node, TransferInput<CFValue, CFStore> input) {
    CFValue resultValue = super.visitEqualTo(node, input).getResultValue();
    CFStore thenStore = input.getThenStore();
    CFStore elseStore = input.getElseStore();
    boolean storeChanged = putNullCheckResult(elseStore, node);
    return new ConditionalTransferResult<>(resultValue, thenStore, elseStore, storeChanged);
  }

  @Override
  public TransferResult<CFValue, CFStore> visitNotEqual(
      NotEqualNode node, TransferInput<CFValue, CFStore> input) {
    CFValue resultValue = super.visitNotEqual(node, input).getResultValue();
    CFStore thenStore = input.getThenStore();
    CFStore elseStore = input.getElseStore();
    boolean storeChanged = putNullCheckResult(thenStore, node);
    return new ConditionalTransferResult<>(resultValue, thenStore, elseStore, storeChanged);
  }

  /** If one operand is a null literal, marks the other as non-null in the given store. */
  private boolean putNullCheckResult(CFStore store, BinaryOperationNode node) {
    if (isNullLiteral(node.getLeftOperand())) {
      return putNonNull(store, node.getRightOperand());
    } else if (isNullLiteral(node.getRightOperand())) {
      return putNonNull(store, node.getLeftOperand());
    }
    return false;
  }

  /** Mark the node as non-null, and return whether this is a change in its value. */
  private boolean putNonNull(CFStore store, Node node) {
    boolean storeChanged = false;
    if (node instanceof ArrayAccessNode
        || node instanceof FieldAccessNode
        || node instanceof LocalVariableNode) {
      Receiver receiver = internalReprOf(atypeFactory, node);
      CFValue oldValue = store.getValue(receiver);
      storeChanged = !alreadyKnownToBeNonNull(oldValue);
      store.insertValue(receiver, nonNull);
    }
    return storeChanged;
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
}
