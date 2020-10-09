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

import com.google.jspecify.nullness.qual.NoAdditionalNullness;
import com.google.jspecify.nullness.qual.Nullable;
import com.google.jspecify.nullness.qual.NullnessUnspecified;
import javax.lang.model.element.AnnotationMirror;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.flow.CFAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFTransfer;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.util.MultiGraphQualifierHierarchy;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;

public class NullSpecAnnotatedTypeFactory
        extends GenericAnnotatedTypeFactory<CFValue, CFStore, CFTransfer, CFAnalysis> {

    protected final AnnotationMirror NONNULL;
    protected final AnnotationMirror NULLABLE;
    protected final AnnotationMirror NULLNESSUNSPECIFIED;

    protected final boolean strictNonNull;

    public NullSpecAnnotatedTypeFactory(BaseTypeChecker checker) {
        // Only use flow-sensitive type refinement if implementation code should be checked
        super(checker, checker.hasOption("checkImpl"));
        NONNULL = AnnotationBuilder.fromClass(elements, NoAdditionalNullness.class);
        NULLABLE = AnnotationBuilder.fromClass(elements, Nullable.class);
        NULLNESSUNSPECIFIED = AnnotationBuilder.fromClass(elements, NullnessUnspecified.class);

        addAliasedAnnotation(org.jspecify.annotations.Nullable.class, NULLABLE);
        addAliasedAnnotation(
                org.jspecify.annotations.NullnessUnspecified.class, NULLNESSUNSPECIFIED);

        if (checker.hasOption("aliasCFannos")) {
            addAliasedAnnotation(
                    org.checkerframework.checker.nullness.qual.Nullable.class, NULLABLE);
        }

        strictNonNull = checker.hasOption("strict");

        postInit();
    }

    @Override
    public QualifierHierarchy createQualifierHierarchy(
            MultiGraphQualifierHierarchy.MultiGraphFactory factory) {
        return new NullSpecQualifierHierarchy(factory, (Object[]) null);
    }

    protected class NullSpecQualifierHierarchy extends MultiGraphQualifierHierarchy {
        protected NullSpecQualifierHierarchy(MultiGraphFactory f, Object[] arg) {
            super(f, arg);
        }

        @Override
        public boolean isSubtype(AnnotationMirror subAnno, AnnotationMirror superAnno) {
            if (!strictNonNull
                    && (AnnotationUtils.areSame(subAnno, NULLNESSUNSPECIFIED)
                            || AnnotationUtils.areSame(superAnno, NULLNESSUNSPECIFIED))) {
                return true;
            }
            return super.isSubtype(subAnno, superAnno);
        }
    }
}
