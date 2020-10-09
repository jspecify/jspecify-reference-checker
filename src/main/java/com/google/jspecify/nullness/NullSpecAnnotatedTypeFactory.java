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
import static org.checkerframework.javacutil.AnnotationUtils.areSame;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.lang.annotation.Annotation;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.util.Elements;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.flow.CFAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFTransfer;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.AnnotatedTypeFormatter;
import org.checkerframework.framework.type.DefaultAnnotatedTypeFormatter;
import org.checkerframework.framework.type.ElementQualifierHierarchy;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.util.AnnotationFormatter;
import org.checkerframework.framework.util.DefaultAnnotationFormatter;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;

public final class NullSpecAnnotatedTypeFactory
        extends GenericAnnotatedTypeFactory<CFValue, CFStore, CFTransfer, CFAnalysis> {
    private final AnnotationMirror noAdditionalNullness;
    private final AnnotationMirror unionNull;
    private final AnnotationMirror codeNotNullnessAware;

    private final boolean leastConvenientWorld;

    public NullSpecAnnotatedTypeFactory(BaseTypeChecker checker) {
        // Only use flow-sensitive type refinement if implementation code should be checked
        super(checker, checker.hasOption("checkImpl"));

        /*
         * The names here ("codeNotNullnessAware", etc.) are less ambiguous than the annotation
         * names ("NullnessUnspecified," etc.): If a type has nullness codeNotNullnessAware, that
         * doesn't necessarily mean that it has the @NullnessUnspecified annotation written on it in
         * source code. The other possibility is that it gets that value from the default in effect.
         *
         * Still, for the annotations that are actually printed in error messages (that is, the ones
         * without @InvisibleQualifier), we want the user-facing class names to match what users at
         * least *could* write in source code. So we use the less ambiguous names for our fields
         * here while still using the user-facing names for most of the classes.
         *
         * (An alternative would be to write a custom AnnotationFormatter to replace the names, but
         * that sounds like more trouble than it's worth.)
         */
        noAdditionalNullness = AnnotationBuilder.fromClass(elements, NoAdditionalNullness.class);
        unionNull = AnnotationBuilder.fromClass(elements, Nullable.class);
        codeNotNullnessAware = AnnotationBuilder.fromClass(elements, NullnessUnspecified.class);

        addAliasedAnnotation(org.jspecify.annotations.Nullable.class, unionNull);
        addAliasedAnnotation(org.jspecify.annotations.NullnessUnspecified.class,
            codeNotNullnessAware);

        if (checker.hasOption("aliasCFannos")) {
            addAliasedAnnotation(
                    org.checkerframework.checker.nullness.qual.Nullable.class, unionNull);
        }

        leastConvenientWorld = checker.hasOption("strict");

        postInit();
    }

    @Override
    protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
        return new LinkedHashSet<>(
            asList(Nullable.class, NullnessUnspecified.class, NoAdditionalNullness.class));
    }

    @Override
    protected QualifierHierarchy createQualifierHierarchy() {
        return new NullSpecQualifierHierarchy(getSupportedTypeQualifiers(), elements);
    }

    private final class NullSpecQualifierHierarchy extends ElementQualifierHierarchy {
        NullSpecQualifierHierarchy(Collection<Class<? extends Annotation>> qualifierClasses, Elements elements) {
            super(qualifierClasses, elements);
        }

        @Override
        public boolean isSubtype(AnnotationMirror subAnno, AnnotationMirror superAnno) {
            boolean subIsUnspecified = areSame(subAnno, codeNotNullnessAware);
            boolean superIsUnspecified = areSame(superAnno, codeNotNullnessAware);
            boolean eitherIsUnspecified = subIsUnspecified || superIsUnspecified;
            boolean bothAreUnspecified = subIsUnspecified && superIsUnspecified;
            if (leastConvenientWorld && bothAreUnspecified) {
                return false;
            }
            if (!leastConvenientWorld && eitherIsUnspecified) {
                return true;
            }
            return areSame(subAnno, noAdditionalNullness) || areSame(superAnno, unionNull);
        }

        @Override
        public AnnotationMirror leastUpperBound(
            AnnotationMirror qualifier1, AnnotationMirror qualifier2) {
            if (!areSame(getTopAnnotation(qualifier1), unionNull) || !areSame(getTopAnnotation(qualifier2),
                unionNull)) {
                return null;
            }
            if (areSame(qualifier1, unionNull) || areSame(qualifier2, unionNull)) {
                return unionNull;
            }
            if (areSame(qualifier1, codeNotNullnessAware) || areSame(qualifier2,
                codeNotNullnessAware)) {
                return codeNotNullnessAware;
            }
            return noAdditionalNullness;
        }

        @Override
        public AnnotationMirror greatestLowerBound(
            AnnotationMirror qualifier1, AnnotationMirror qualifier2) {
            if (!areSame(getTopAnnotation(qualifier1), unionNull) || !areSame(getTopAnnotation(qualifier2),
                unionNull)) {
                return null;
            }
            if (areSame(qualifier1, noAdditionalNullness) || areSame(qualifier2,
                noAdditionalNullness)) {
                return noAdditionalNullness;
            }
            if (areSame(qualifier1, codeNotNullnessAware) || areSame(qualifier2,
                codeNotNullnessAware)) {
                return codeNotNullnessAware;
            }
            return unionNull;
        }
    }

    @Override
    protected AnnotationFormatter createAnnotationFormatter() {
        return new DefaultAnnotationFormatter() {
            @Override
            public String formatAnnotationString(Collection<? extends AnnotationMirror> annos,
                boolean printInvisible) {
                return super.formatAnnotationString(annos, /*printInvisible=*/ false);
            }
        };
    }

    @Override
    protected AnnotatedTypeFormatter createAnnotatedTypeFormatter() {
        return new DefaultAnnotatedTypeFormatter(
            /*
             * We would pass the result of getAnnotationFormatter(), but the superclass calls
             * createAnnotatedTypeFormatter() before it initializes that field.
             *
             * Fortunately, it's harmless to use one AnnotationFormatter here and another equivalent
             * one in createAnnotationFormatter().
             */
            createAnnotationFormatter(),
            // TODO(cpovirk): Permit configuration of these booleans?
            /*printVerboseGenerics=*/ false, /*defaultPrintInvisibleAnnos=*/ false);
    }
}
