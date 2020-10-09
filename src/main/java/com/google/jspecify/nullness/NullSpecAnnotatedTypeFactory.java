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
import static javax.lang.model.element.ElementKind.PACKAGE;
import static javax.lang.model.type.TypeKind.WILDCARD;
import static org.checkerframework.framework.qual.TypeUseLocation.OTHERWISE;
import static org.checkerframework.javacutil.AnnotationUtils.areSame;

import com.sun.source.tree.Tree;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.lang.annotation.Annotation;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.flow.CFAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFTransfer;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeFormatter;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedPrimitiveType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedTypeVariable;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedWildcardType;
import org.checkerframework.framework.type.DefaultAnnotatedTypeFormatter;
import org.checkerframework.framework.type.ElementQualifierHierarchy;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.StructuralEqualityComparer;
import org.checkerframework.framework.type.StructuralEqualityVisitHistory;
import org.checkerframework.framework.type.TypeVariableSubstitutor;
import org.checkerframework.framework.type.typeannotator.TypeAnnotator;
import org.checkerframework.framework.type.visitor.AnnotatedTypeScanner;
import org.checkerframework.framework.util.AnnotationFormatter;
import org.checkerframework.framework.util.DefaultAnnotationFormatter;
import org.checkerframework.framework.util.defaults.QualifierDefaults;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.jspecify.annotations.DefaultNonNull;

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

    private final class NullSpecEqualityComparer extends StructuralEqualityComparer {
        NullSpecEqualityComparer(StructuralEqualityVisitHistory typeargVisitHistory) {
            super(typeargVisitHistory);
        }

        @Override
        protected boolean checkOrAreEqual(AnnotatedTypeMirror type1,
            AnnotatedTypeMirror type2) {
            Boolean pastResult = visitHistory.result(type1, type2, /*hierarchy=*/ unionNull);
            if (pastResult != null) {
                return pastResult;
            }

            boolean result = areEqual(type1, type2);
            this.visitHistory.add(type1, type2, /*hierarchy=*/ unionNull, result);
            return result;
        }

        @Override
        public boolean areEqualInHierarchy(AnnotatedTypeMirror type1,
            AnnotatedTypeMirror type2, AnnotationMirror top) {
            return areEqual(type1, type2);
        }

        private boolean areEqual(AnnotatedTypeMirror type1, AnnotatedTypeMirror type2) {
            /*
             * I'd like to use the spec definition here: "type1 is a subtype of type2 and vice
             * versa." However, that produces infinite recursion in some cases.
             */
            boolean type1IsUnspecified = type1.hasAnnotation(codeNotNullnessAware);
            boolean type2IsUnspecified = type2.hasAnnotation(codeNotNullnessAware);
            boolean bothAreUnspecified = type1IsUnspecified && type2IsUnspecified;
            boolean eitherIsUnspecified = type1IsUnspecified || type2IsUnspecified;
            if (leastConvenientWorld && bothAreUnspecified) {
                return false;
            }
            if (!leastConvenientWorld && eitherIsUnspecified) {
                return true;
            }
            AnnotationMirror a1 = type1.getAnnotationInHierarchy(unionNull);
            AnnotationMirror a2 = type2.getAnnotationInHierarchy(unionNull);
            return a1 == a2 || (a1 != null && a2 != null && areSame(a1, a2));
            /*
             * TODO(cpovirk): Do we care about the base type, or is looking at annotations
             * enough? super.visitDeclared_Declared has a TODO with a similar question.
             * Err, presumably normal Java type-checking has done that job. A more interesting
             * question may be why we don't look at type args. The answer might be simply:
             * "That's the contract, even though it is surprising, given the names of the class
             * and its methods." (Granted, the docs of super.visitDeclared_Declared also say
             * that it checks that "The types are of the same class/interfaces," so the contract
             * isn't completely clear.)
             */
        }
    }

    @Override
    protected TypeVariableSubstitutor createTypeVariableSubstitutor() {
        return new NullSpecTypeVariableSubstitutor();
    }

    private final class NullSpecTypeVariableSubstitutor extends TypeVariableSubstitutor {
        @Override
        protected AnnotatedTypeMirror substituteTypeVariable(AnnotatedTypeMirror argument,
            AnnotatedTypeVariable use) {
            // TODO(cpovirk): Delegate to leastUpperBound?
            AnnotatedTypeMirror substitute = argument.deepCopy(/*copyAnnotations=*/ true);
            if (argument.hasAnnotation(unionNull) || use.hasAnnotation(unionNull)) {
                substitute.replaceAnnotation(unionNull);
            } else if (argument.hasAnnotation(codeNotNullnessAware) || use
                .hasAnnotation(codeNotNullnessAware)) {
                substitute.replaceAnnotation(codeNotNullnessAware);
            }

            return substitute;
        }
    }

    @Override
    public AnnotatedDeclaredType getSelfType(Tree tree) {
        AnnotatedDeclaredType superResult = super.getSelfType(tree);
        return superResult == null ? null : withNoAdditionalNullness(superResult);
    }

    @Override
    protected QualifierDefaults createQualifierDefaults() {
        return new NullSpecQualifierDefaults(elements, this);
    }

    private final class NullSpecQualifierDefaults extends QualifierDefaults {
        NullSpecQualifierDefaults(Elements elements, AnnotatedTypeFactory atypeFactory) {
            super(elements, atypeFactory);
        }

        @Override
        public void annotate(Element elt, AnnotatedTypeMirror type) {
            if (elt == null) {
                super.annotate(elt, type);
                return;
            }

            writeDefaultsCommonToNullAwareAndNonNullAwareCode(type);

            /*
             * XXX: When adding support for NotNullAware, be sure that each of those annotations on
             * a *class* overrides the other on the *package*.
             */
            boolean hasNullAwareAnnotation = elt.getAnnotation(DefaultNonNull.class) != null || (
                elt.getEnclosingElement().getKind() == PACKAGE &&
                    elt.getEnclosingElement().getAnnotation(DefaultNonNull.class) != null);
            if (hasNullAwareAnnotation) {
                /*
                 * Setting a default here affects not only this element but also its descendants in
                 * the syntax tree.
                 */
                // TODO(cpovirk): Default unbounded wildcards' upper bounds to @Nullable.
                addElementDefault(elt, noAdditionalNullness, OTHERWISE);
            }

            super.annotate(elt, type);
        }

        @Override
        public void annotate(Tree tree, AnnotatedTypeMirror type) {
            writeDefaultsCommonToNullAwareAndNonNullAwareCode(type);
            super.annotate(tree, type);
        }

        void writeDefaultsCommonToNullAwareAndNonNullAwareCode(AnnotatedTypeMirror type) {
            new AnnotatedTypeScanner<Void, Void>() {
                @Override
                public Void visitDeclared(AnnotatedDeclaredType type, Void p) {
                    AnnotatedDeclaredType enclosingType = type.getEnclosingType();
                    if (enclosingType != null) {
                        addIfNoAnnotationPresent(enclosingType, noAdditionalNullness);
                    }
                    return super.visitDeclared(type, p);
                }

                @Override
                public Void visitPrimitive(AnnotatedPrimitiveType type, Void p) {
                    addIfNoAnnotationPresent(type, noAdditionalNullness);
                    return super.visitPrimitive(type, p);
                }

                @Override
                public Void visitWildcard(AnnotatedWildcardType type, Void p) {
                    if (type.getUnderlyingType().getSuperBound() != null) {
                        addIfNoAnnotationPresent(type.getExtendsBound(), unionNull);
                    }
                    return super.visitWildcard(type, p);
                }
            }.visit(type);
        }

        void addIfNoAnnotationPresent(AnnotatedTypeMirror type, AnnotationMirror annotation) {
            if (!type.isAnnotatedInHierarchy(unionNull)) {
                type.addAnnotation(annotation);
            }
        }

        @Override
        protected DefaultApplierElement createDefaultApplierElement(
            AnnotatedTypeFactory atypeFactory, Element annotationScope,
            AnnotatedTypeMirror type,
            boolean applyToTypeVar) {
            return new DefaultApplierElement(atypeFactory, annotationScope, type,
                applyToTypeVar) {
                @Override
                protected boolean shouldBeAnnotated(AnnotatedTypeMirror type,
                    boolean applyToTypeVar) {
                    return super.shouldBeAnnotated(type, /*applyToTypeVar=*/ true);
                }
            };
        }

        // TODO(cpovirk): Should I override applyConservativeDefaults to always return false?
    }

    @Override
    protected void addComputedTypeAnnotations(Tree tree, AnnotatedTypeMirror type,
        boolean iUseFlow) {
        // TODO(cpovirk): This helps, but why?
        super.addComputedTypeAnnotations(tree, type, iUseFlow && type.getKind() != WILDCARD);
    }

    @Override
    protected TypeAnnotator createTypeAnnotator() {
        /*
         * Override to do nothing. The supermethod adds the top type (@Nullable/unionNull) to the
         * bound of unbounded wildcards, but we want the ability to sometimes add
         * @NullnessUnspecified/codeNotNullnessAware instead.
         */
        return new TypeAnnotator(this) {};
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

    @SuppressWarnings("unchecked") // safety guaranteed by API docs
    private <T extends AnnotatedTypeMirror> T withNoAdditionalNullness(T type) {
        // Remove the annotation from the *root* type, but preserve other annotations.
        type = (T) type.deepCopy(/*copyAnnotations=*/ true);
        type.replaceAnnotation(noAdditionalNullness);
        return type;
    }

    @SuppressWarnings("unchecked") // safety guaranteed by API docs
    private <T extends AnnotatedTypeMirror> T withUnionNull(T type) {
        // Remove the annotation from the *root* type, but preserve other annotations.
        type = (T) type.deepCopy(/*copyAnnotations=*/ true);
        type.replaceAnnotation(unionNull);
        return type;
    }
}
