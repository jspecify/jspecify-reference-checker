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

import static javax.lang.model.element.ElementKind.CLASS;
import static org.checkerframework.javacutil.TreeUtils.elementFromTree;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssertTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import javax.lang.model.element.ExecutableElement;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;

// Option to forbid explicit usage of @NoAdditionalNullness (and...?)
// Option to make @NullAnnotated the default or not
public final class NullSpecVisitor extends BaseTypeVisitor<NullSpecAnnotatedTypeFactory> {
    private final boolean checkImpl;

    public NullSpecVisitor(BaseTypeChecker checker) {
        super(checker);
        checkImpl = checker.hasOption("checkImpl");
    }

    private void ensureNonNull(Tree tree, String messageKeyPart) {
        AnnotatedTypeMirror type = atypeFactory.getAnnotatedType(tree);
        if (!atypeFactory.isNullExclusiveUnderEveryParameterization(type)) {
            // TODO(cpovirk): Put the type in the body of the message once possible
            checker.reportError(tree, "possibly.null." + messageKeyPart + ": " + type);
        }
    }

    public Void visitBlock(BlockTree node, Void p) {
        if (checkImpl) {
            return super.visitBlock(node, p);
        } else {
            return null;
        }
    }

    @Override
    protected void checkConstructorResult(
            AnnotatedExecutableType constructorType, ExecutableElement constructorElement) {
        // TODO: ensure no explicit annotations on class & constructor
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree node, Void p) {
        if (elementFromTree(node).getKind() != CLASS) {
            ensureNonNull(node.getExpression(), "member.select");
            /*
             * By contrast, if it's CLASS, the select must be on a type, like `Foo.Baz` or
             * `Foo<Bar>.Baz`. We don't need to check that the type is non-null because the code is
             * not actually dereferencing anything.
             *
             * In fact, a check that the type is non-null is currently not *safe*: The outer type
             * appears to default to NullnessUnspecified in non-null-aware code.
             *
             * Note that our defaulting of enclosing types in
             * writeDefaultsForIntrinsicallyNonNullableComponents does not help. It does not help
             * even when I retrieve the type of the entire MemberSelectTree (and then pull out the
             * outer type from that).
             *
             * The code path that we end up in appears to be looking specifically at the class
             * referenced by the MemberSelectTree, without regard to any annotations on, e.g., the
             * VariableTree that it is the type for. We end up in AnnotatedTypeFactory.fromElement.
             * Possibly that's bogus: Not every MemberSelectTree is an "expression" in the usual
             * sense. Perhaps it's our job not to call getAnnotatedType on such trees? So let's not.
             */
        }
        return super.visitMemberSelect(node, p);
    }

    @Override
    public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void p) {
        ensureNonNull(node.getExpression(), "enhanced.for");
        return super.visitEnhancedForLoop(node, p);
    }

    @Override
    public Void visitArrayAccess(ArrayAccessTree node, Void p) {
        ensureNonNull(node.getExpression(), "array.access");
        return super.visitArrayAccess(node, p);
    }

    @Override
    protected void checkThrownExpression(ThrowTree node) {
        ensureNonNull(node.getExpression(), "thrown.expression");
    }

    @Override
    public Void visitSynchronized(SynchronizedTree node, Void p) {
        ensureNonNull(node.getExpression(), "synchronized");
        return super.visitSynchronized(node, p);
    }

    @Override
    public Void visitAssert(AssertTree node, Void p) {
        ensureNonNull(node.getCondition(), "assert.condition");
        if (node.getDetail() != null) {
            ensureNonNull(node.getDetail(), "assert.detail");
        }
        return super.visitAssert(node, p);
    }

    @Override
    public Void visitIf(IfTree node, Void p) {
        ensureNonNull(node.getCondition(), "if.condition");
        return super.visitIf(node, p);
    }

    // TODO: binary, unary, compoundassign, typecast, ...
}
