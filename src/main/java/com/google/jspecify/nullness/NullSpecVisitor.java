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

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssertTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import javax.lang.model.element.ExecutableElement;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;

// Option to forbid explicit usage of @NoAdditionalNullness (and...?)
// Option to make @NullAnnotated the default or not
public final class NullSpecVisitor extends BaseTypeVisitor<NullSpecAnnotatedTypeFactory> {
    private final boolean strictNonNull;
    private final boolean checkImpl;

    public NullSpecVisitor(BaseTypeChecker checker) {
        super(checker);
        strictNonNull = checker.hasOption("strict");
        checkImpl = checker.hasOption("checkImpl");
    }

    private void ensureNonNull(ExpressionTree tree, String messagekeypart) {
        AnnotatedTypeMirror type = atypeFactory.getAnnotatedType(tree);
        if (strictNonNull && !type.hasEffectiveAnnotation(NoAdditionalNullness.class)) {
            checker.reportError(tree, "not.nonnull." + messagekeypart);
        }
        if (!strictNonNull && type.hasEffectiveAnnotation(Nullable.class)) {
            checker.reportError(tree, "nullable." + messagekeypart);
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
            ensureNonNull(node.getExpression(), "member.select");
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
