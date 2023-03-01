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

import static com.google.jspecify.nullness.NullSpecAnnotatedTypeFactory.IsDeclaredOrArray.IS_DECLARED_OR_ARRAY;
import static com.google.jspecify.nullness.Util.IMPLEMENTATION_VARIABLE_LOCATIONS;
import static com.google.jspecify.nullness.Util.nameMatches;
import static com.sun.source.tree.Tree.Kind.CONDITIONAL_EXPRESSION;
import static com.sun.source.tree.Tree.Kind.IDENTIFIER;
import static com.sun.source.tree.Tree.Kind.MEMBER_SELECT;
import static com.sun.source.tree.Tree.Kind.NOT_EQUAL_TO;
import static com.sun.source.tree.Tree.Kind.NULL_LITERAL;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableList;
import static javax.lang.model.element.ElementKind.ENUM_CONSTANT;
import static javax.lang.model.type.TypeKind.ARRAY;
import static javax.lang.model.type.TypeKind.DECLARED;
import static javax.lang.model.type.TypeKind.TYPEVAR;
import static javax.lang.model.type.TypeKind.WILDCARD;
import static org.checkerframework.framework.qual.TypeUseLocation.CONSTRUCTOR_RESULT;
import static org.checkerframework.framework.qual.TypeUseLocation.EXCEPTION_PARAMETER;
import static org.checkerframework.framework.qual.TypeUseLocation.IMPLICIT_LOWER_BOUND;
import static org.checkerframework.framework.qual.TypeUseLocation.OTHERWISE;
import static org.checkerframework.framework.qual.TypeUseLocation.RECEIVER;
import static org.checkerframework.framework.util.AnnotatedTypes.asSuper;
import static org.checkerframework.framework.util.defaults.QualifierDefaults.AdditionalTypeUseLocation.UNBOUNDED_WILDCARD_UPPER_BOUND;
import static org.checkerframework.javacutil.AnnotationUtils.areSame;
import static org.checkerframework.javacutil.AnnotationUtils.areSameByName;
import static org.checkerframework.javacutil.TreePathUtil.enclosingClass;
import static org.checkerframework.javacutil.TreeUtils.elementFromDeclaration;
import static org.checkerframework.javacutil.TreeUtils.elementFromUse;
import static org.checkerframework.javacutil.TreeUtils.isNullExpression;
import static org.checkerframework.javacutil.TreeUtils.typeOf;
import static org.checkerframework.javacutil.TypesUtils.isCapturedTypeVariable;
import static org.checkerframework.javacutil.TypesUtils.isPrimitive;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.lang.model.AnnotatedConstruct;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.flow.CFAbstractAnalysis;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeFormatter;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedArrayType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedIntersectionType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedNoType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedNullType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedPrimitiveType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedTypeVariable;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedUnionType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedWildcardType;
import org.checkerframework.framework.type.DefaultTypeHierarchy;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.framework.type.NoElementQualifierHierarchy;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.StructuralEqualityComparer;
import org.checkerframework.framework.type.StructuralEqualityVisitHistory;
import org.checkerframework.framework.type.TypeHierarchy;
import org.checkerframework.framework.type.TypeVariableSubstitutor;
import org.checkerframework.framework.type.treeannotator.ListTreeAnnotator;
import org.checkerframework.framework.type.treeannotator.TreeAnnotator;
import org.checkerframework.framework.type.typeannotator.TypeAnnotator;
import org.checkerframework.framework.type.visitor.AnnotatedTypeVisitor;
import org.checkerframework.framework.util.AnnotationFormatter;
import org.checkerframework.framework.util.DefaultAnnotationFormatter;
import org.checkerframework.framework.util.DefaultQualifierKindHierarchy;
import org.checkerframework.framework.util.QualifierKindHierarchy;
import org.checkerframework.framework.util.defaults.QualifierDefaults;

final class NullSpecAnnotatedTypeFactory
    extends GenericAnnotatedTypeFactory<
        CFValue, NullSpecStore, NullSpecTransfer, NullSpecAnalysis> {
  private final Util util;

  private final AnnotationMirror minusNull;
  private final AnnotationMirror unionNull;
  private final AnnotationMirror nullnessOperatorUnspecified;

  private final boolean isLeastConvenientWorld;
  private final NullSpecAnnotatedTypeFactory withLeastConvenientWorld;
  private final NullSpecAnnotatedTypeFactory withMostConvenientWorld;

  private final AnnotatedDeclaredType javaUtilCollection;

  final AnnotatedDeclaredType javaLangClass;
  final AnnotatedDeclaredType javaLangThreadLocal;
  final AnnotatedDeclaredType javaUtilMap;

  /** Constructor that takes all configuration from the provided {@code checker}. */
  NullSpecAnnotatedTypeFactory(BaseTypeChecker checker, Util util) {
    this(checker, util, checker.hasOption("strict"), /*withOtherWorld=*/ null);
  }

  /**
   * Constructor that takes all configuration from the provided {@code checker} <i>except</i> {@code
   * strict}/{@code isLeastConvenientWorld}. It also accepts another instance of this class (if one
   * is already available) that represents the other "world."
   */
  private NullSpecAnnotatedTypeFactory(
      BaseTypeChecker checker,
      Util util,
      boolean isLeastConvenientWorld,
      NullSpecAnnotatedTypeFactory withOtherWorld) {
    // Only use flow-sensitive type refinement if implementation code should be checked
    super(checker, checker.hasOption("checkImpl"));

    this.util = util;

    minusNull = util.minusNull;
    unionNull = util.unionNull;
    nullnessOperatorUnspecified = util.nullnessOperatorUnspecified;

    addAliasedTypeAnnotation(
        "org.jspecify.annotations.NullnessUnspecified", nullnessOperatorUnspecified);
    addAliasedTypeAnnotation(
        "org.jspecify.nullness.NullnessUnspecified", nullnessOperatorUnspecified);

    // Yes, it's valid to pass declaration annotations to addAliased*Type*Annotation.
    NULLABLE_ANNOTATIONS.forEach(a -> addAliasedTypeAnnotation(a, unionNull));
    NOT_NULL_ANNOTATIONS.forEach(a -> addAliasedTypeAnnotation(a, minusNull));
    /*
     * TODO(cpovirk): If we rework how we read annotations on a deep enough level, consider
     * recognizing annotations by simple class name instead of by fully qualified name.
     */

    this.isLeastConvenientWorld = isLeastConvenientWorld;

    javaUtilCollection = createType(util.javaUtilCollectionElement);
    javaLangClass = createType(util.javaLangClassElement);
    javaLangThreadLocal = createType(util.javaLangThreadLocalElement);
    javaUtilMap = createType(util.javaUtilMapElement);

    /*
     * Creating a new AnnotatedTypeFactory is expensive -- especially parseStubFiles. So we make
     * sure to create only a single instance for each "world."
     *
     * It would be better if we could operate in both "worlds" without needing to create 2 separate
     * AnnotatedTypeFactory instances. But I worry about accidentally depending on state that is
     * specific to the world that the current instance was created with.
     */
    boolean givenOtherWorld = withOtherWorld != null;
    if (!givenOtherWorld) {
      withOtherWorld =
          new NullSpecAnnotatedTypeFactory(
              checker, util, !isLeastConvenientWorld, /*withOtherWorld=*/ this);
    }
    if (isLeastConvenientWorld) {
      withLeastConvenientWorld = this;
      withMostConvenientWorld = withOtherWorld;
    } else {
      withLeastConvenientWorld = withOtherWorld;
      withMostConvenientWorld = this;
    }

    if (!givenOtherWorld) {
      /*
       * Now the withLeastConvenientWorld and withMostConvenientWorld fields of both `this` and
       * `withOtherWorld` have been populated. It's safe to call postInit() for each.
       *
       * In contrast, if we *were* given another world, that would mean that we are being called
       * from within the other world's constructor. In that case, the other world hasn't yet
       * populated its fields, so it's not safe for us to call postInit() on it, nor on `this`
       * (since `this` might call into the other world).
       *
       * (It's possible that this is slightly over-conservative: We *typically* don't call from the
       * least convenient world into the most convenient world. But we do during dataflow (though
       * that *might* not run during init?) and perhaps elsewhere. It seems safest to populate as
       * many fields as we can before calling postInit().)
       *
       * (It's also possible that this is not conservative enough: Maybe we need to *fully*
       * initialize each object before we initialize the other. If so, we have circular
       * dependencies. If we're lucky, that won't come up. If we're unlucky, maybe it will at least
       * come up only "in one direction," meaning that it would be safe to fully initialize the
       * least convenient world and then subsequently fully initialize the most convenient world (or
       * the other order if that's the one that works).)
       */
      withOtherWorld.postInit();
      postInit();
    }
  }

  @Override
  protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
    return new LinkedHashSet<>(asList(Nullable.class, NullnessUnspecified.class, MinusNull.class));
  }

  @Override
  protected QualifierHierarchy createQualifierHierarchy() {
    return new NullSpecQualifierHierarchy(getSupportedTypeQualifiers(), elements);
  }

  private final class NullSpecQualifierHierarchy extends NoElementQualifierHierarchy {
    NullSpecQualifierHierarchy(
        Collection<Class<? extends Annotation>> qualifierClasses, Elements elements) {
      super(qualifierClasses, elements);
    }

    @Override
    public boolean isSubtype(AnnotationMirror subAnno, AnnotationMirror superAnno) {
      if (subAnno == null || superAnno == null) {
        /*
         * The stock CF never passes null to this method: It always expose *some* annotation
         * (perhaps an upper bound?) associated with a given type. However, *we*, in the case of
         * unannotated type-variable usages, do not expose an annotation where we should. The lack
         * of annotation manifests as a null argument to this method.
         *
         * Surprisingly, this doesn't seem to cause a ton of issues in practice. Maybe the typical
         * case is that we're comparing 2 types that *both* lack an annotation, and maybe that's
         * enough for CF to short-circuit before calling this method? The case in which we *have*
         * seen problems is when we mix code with unspecified nullness and code with specified
         * nullness -- but even then only in certain situations, including some usages of the
         * ternary operator.
         *
         * Eventually, we should figure out how this method should behave in the null case. Or
         * really, what we "should" do is change our checker to fundamentally mesh with CF's design.
         * (Some of this is discussed in https://github.com/jspecify/checker-framework/issues/4)
         *
         * For now, we just want to avoid having the checker crash with a NullPointerException, so
         * we just return true (hopefully a good guess in lenient mode, less so in strict mode).
         *
         * TODO(cpovirk): Handle or avoid the case of null arguments.
         */
        return true;
      }
      /*
       * Since we perform all necessary checking in the isSubtype method in NullSpecTypeHierarchy, I
       * tried replacing this body with `return true` to avoid duplicating logic. However, that's a
       * problem because the result of this method is sometimes cached and used instead of a full
       * call to the isSubtype method in NullSpecTypeHierarchy.
       *
       * Specifically: DefaultTypeHierarchy.visitDeclared_Declared calls isPrimarySubtype, which
       * calls isAnnoSubtype, which directly calls NullSpecQualifierHierarchy.isSubtype (as opposed
       * to NullSpecTypeHierarchy.isSubtype). That's still fine, since we'll reject the types in
       * NullSpecTypeHierarchy.isSubtype. The problem, though, is that it also inserts a cache entry
       * for the supposed subtyping relationship, and that entry can cause future checks to
       * short-circuit. (I think I saw this in isContainedBy.)
       */
      boolean subIsUnspecified = areSame(subAnno, nullnessOperatorUnspecified);
      boolean superIsUnspecified = areSame(superAnno, nullnessOperatorUnspecified);
      boolean eitherIsUnspecified = subIsUnspecified || superIsUnspecified;
      boolean bothAreUnspecified = subIsUnspecified && superIsUnspecified;
      if (isLeastConvenientWorld && bothAreUnspecified) {
        return false;
      }
      if (!isLeastConvenientWorld && eitherIsUnspecified) {
        return true;
      }
      return areSame(subAnno, minusNull) || areSame(superAnno, unionNull);
    }

    @Override
    protected QualifierKindHierarchy createQualifierKindHierarchy(
        Collection<Class<? extends Annotation>> qualifierClasses) {
      return new DefaultQualifierKindHierarchy(qualifierClasses, /*bottom=*/ MinusNull.class) {
        @Override
        protected Map<DefaultQualifierKind, Set<DefaultQualifierKind>> createDirectSuperMap() {
          DefaultQualifierKind minusNullKind =
              nameToQualifierKind.get(MinusNull.class.getCanonicalName());
          DefaultQualifierKind unionNullKind =
              nameToQualifierKind.get(Nullable.class.getCanonicalName());
          DefaultQualifierKind nullnessOperatorUnspecified =
              nameToQualifierKind.get(NullnessUnspecified.class.getCanonicalName());

          Map<DefaultQualifierKind, Set<DefaultQualifierKind>> supers = new HashMap<>();
          supers.put(minusNullKind, singleton(nullnessOperatorUnspecified));
          supers.put(nullnessOperatorUnspecified, singleton(unionNullKind));
          supers.put(unionNullKind, emptySet());
          return supers;
          /*
           * The rules above are incomplete:
           *
           * - In "lenient mode," we treat unionNull as a subtype of codeNotNullnesesAware.
           *
           * - In "strict mode," we do *not* treat codeNotNullnesesAware as a subtype of itself.
           *
           * These subtleties are handled by isSubtype above. The incomplete rules still provide us
           * with useful implementations of leastUpperBound and greatestLowerBound.
           */
        }
      };
    }
  }

  @Override
  protected TypeHierarchy createTypeHierarchy() {
    return new NullSpecTypeHierarchy(
        checker,
        getQualifierHierarchy(),
        checker.getBooleanOption("ignoreRawTypeArguments", true),
        checker.hasOption("invariantArrays"));
  }

  private final class NullSpecTypeHierarchy extends DefaultTypeHierarchy {
    NullSpecTypeHierarchy(
        BaseTypeChecker checker,
        QualifierHierarchy qualifierHierarchy,
        boolean ignoreRawTypeArguments,
        boolean invariantArrays) {
      super(checker, qualifierHierarchy, ignoreRawTypeArguments, invariantArrays);
    }

    @Override
    protected StructuralEqualityComparer createEqualityComparer() {
      return new NullSpecEqualityComparer(areEqualVisitHistory);
    }

    @Override
    protected boolean visitTypevar_Type(
        AnnotatedTypeVariable subtype, AnnotatedTypeMirror supertype) {
      /*
       * The superclass "projects" type-variable usages rather than unioning them. Consequently, if
       * we delegate directly to the supermethod, it can fail when it shouldn't.  Fortunately, we
       * already handle the top-level nullness subtyping in isNullnessSubtype. So all we need to do
       * here is to handle any type arguments. To do that, we still delegate to the supertype. But
       * first we mark the supertype as unionNull so that the supertype's top-level check will
       * always succeed.
       *
       * TODO(cpovirk): There are probably many more cases that we could short-circuit. We might
       * consider doing that in isSubtype rather than with overrides.
       */
      return super.visitTypevar_Type(subtype, withUnionNull(supertype));
    }

    @Override
    protected boolean visitWildcard_Type(
        AnnotatedWildcardType subtype, AnnotatedTypeMirror supertype) {
      // See discussion in visitTypevar_Type above.
      return super.visitWildcard_Type(subtype, withUnionNull(supertype));
    }

    @Override
    protected boolean visitType_Typevar(
        AnnotatedTypeMirror subtype, AnnotatedTypeVariable supertype) {
      /*
       * TODO(cpovirk): Why are the supertype cases so different from the subtype cases above? In
       * particular: Why is it important to replace the subtype instead of the supertype?
       */
      return super.visitType_Typevar(withMinusNull(subtype), supertype);
    }

    @Override
    protected boolean visitType_Wildcard(
        AnnotatedTypeMirror subtype, AnnotatedWildcardType supertype) {
      /*
       * See discussion in visitType_Typevar above.
       *
       * Plus: TODO(cpovirk): Why is it important to replace an argument only conditionally?
       */
      return super.visitType_Wildcard(
          isNullInclusiveUnderEveryParameterization(supertype) ? withMinusNull(subtype) : subtype,
          supertype);
    }

    @Override
    public Boolean visitTypevar_Typevar(
        AnnotatedTypeVariable subtype, AnnotatedTypeVariable supertype, Void p) {
      /*
       * Everything we need to check will be handled by isNullnessSubtype. That's fortunate, as the
       * supermethod does not account for our non-standard substitution rules for type variables.
       * Under those rules, `@NullnessUnspecified T` can still produce a @Nullable value after
       * substitution.
       */
      return true;
    }

    @Override
    protected boolean isSubtype(
        AnnotatedTypeMirror subtype, AnnotatedTypeMirror supertype, AnnotationMirror top) {
      return super.isSubtype(subtype, supertype, top) && isNullnessSubtype(subtype, supertype);
    }

    private boolean isNullnessSubtype(AnnotatedTypeMirror subtype, AnnotatedTypeMirror supertype) {
      if (isPrimitive(subtype.getUnderlyingType())) {
        return true;
      }
      if (supertype.getKind() == WILDCARD) {
        /*
         * super.isSubtype already called back into this.isSameType (and thus into
         * isNullnessSubtype) for the bound. That's fortunate, as we don't define subtyping rules
         * for wildcards (since the JLS says that they should be capture converted by this point, or
         * we should be checking their *bounds* for a containment check).
         *
         * Even now that CF has implemented capture conversion, we're still seeing calls to this
         * method with wildcard supertypes, and we still want this special case.
         *
         * Specifically, we see those calls in the case of uninferred type arguments (I think).
         *
         * TODO(cpovirk): So maybe this special case and the isUninferredTypeArgument case below
         * should look more similar now that *all* wildcards seen by this method might be
         * uninferred?
         */
        return true;
      }
      if (subtype instanceof AnnotatedWildcardType
          && ((AnnotatedWildcardType) subtype).isUninferredTypeArgument()) {
        /*
         * Hope for the best, as the supertype does.
         *
         * XXX: I'm not sure if I'm exactly matching the cases in which the supertype checks for
         * uninferred type arguments. But this check is enough to eliminate the compile errors I was
         * seeing in my testing and also not so much that it breaks handling of any of our existing
         * samples.
         */
        return true;
      }
      if (supertype.getKind() == TYPEVAR
          && !supertype.hasAnnotation(minusNull)
          && isNullnessSubtype(subtype, ((AnnotatedTypeVariable) supertype).getLowerBound())) {
        return true;
      }
      return isNullInclusiveUnderEveryParameterization(supertype)
          || isNullExclusiveUnderEveryParameterization(subtype)
          || (nullnessEstablishingPathExists(subtype, supertype)
              && !supertype.hasAnnotation(minusNull));
    }
  }

  boolean isNullInclusiveUnderEveryParameterization(AnnotatedTypeMirror type) {
    // We put the third case from the spec first because it's a mouthful.
    // (As discussed in the spec, we probably don't strictly need this case at all....)
    if (type.getKind() == TYPEVAR
        && !type.hasAnnotation(minusNull)
        && isNullInclusiveUnderEveryParameterization(
            ((AnnotatedTypeVariable) type).getLowerBound())) {
      return true;
    }
    /*
     * Our draft subtyping rules specify a special case for intersection types. However, those rules
     * make sense only because the rules also specify that an intersection type never has an
     * nullness-operator value of its own. This is in contrast to CF, which does let an intersection
     * type have an AnnotationMirror of its own.
     *
     * ...well, sort of. As I understand it, what CF does is more that it tries to keep the
     * AnnotationMirror of the intersecton type in sync with the AnnotationMirror of each of its
     * components (which should themselves all match). So the intersection type "has" an
     * AnnotationMirror, but it provides no *additional* information beyond what is already carried
     * by its components' AnnotationMirrors.
     *
     * Nevertheless, the result is that we don't need a special case here: The check below is
     * redundant with the subsequent check on the intersection's components, but redundancy is
     * harmless.
     */
    return type.hasAnnotation(unionNull)
        || (!isLeastConvenientWorld && type.hasAnnotation(nullnessOperatorUnspecified));
  }

  boolean isNullExclusiveUnderEveryParameterization(AnnotatedTypeMirror type) {
    return nullnessEstablishingPathExists(type, IS_DECLARED_OR_ARRAY);
  }

  private boolean nullnessEstablishingPathExists(
      AnnotatedTypeMirror subtype, AnnotatedTypeMirror supertype) {
    /*
     * TODO(cpovirk): As an optimization, `return false` if `supertype` is not a type variable: If
     * it's not a type variable, then the only ways for isNullnessSubtype to succeed were already
     * checked by isNullInclusiveUnderEveryParameterization and
     * isNullExclusiveUnderEveryParameterization.
     */
    return nullnessEstablishingPathExists(subtype, isSameTypeAs(supertype.getUnderlyingType()));
  }

  private boolean nullnessEstablishingPathExists(
      AnnotatedTypeMirror subtype, Predicate<TypeMirror> supertypeMatcher) {
    /*
     * In most cases, we do not need to check specifically for minusNull because the remainder of
     * the method is sufficient. However, consider a type that meets all 3 of the following
     * criteria:
     *
     * 1. a local variable
     *
     * 2. whose type is a type variable
     *
     * 3. whose corresponding type parameter permits nullable type arguments
     *
     * For such a type, the remainder of the method would always return false. And that makes
     * sense... until an implementation checks it with `if (foo != null)`. At that point, we need to
     * store an additional piece of information: Yes, _the type written in code_ can permit null,
     * but we know from dataflow that _this particular value_ is _not_ null. That additional
     * information is stored by attaching minusNull to the type-variable usage. This produces a type
     * distinct from all of:
     *
     * - `T`: `T` with nullness operator NO_CHANGE
     *
     * - `@NullnessUnspecified T`: `T` with nullness operator CODE_NOT_NULLNESS_AWARE
     *
     * - `@Nullable T`: `T` with nullness operator UNION_NULL
     *
     * It is unfortunate that this forces us to represent type-variable usages differently from how
     * we represent all other types. For all other types, the way to represent a type with nullness
     * operator NO_CHANGE is to attach minusNull. But again, for type-variable usages, the way to do
     * it is to attach *no* annotation.
     *
     * TODO(cpovirk): Is the check for minusNull also important for the special case of minusNull
     * type-variable usages generated by substituteTypeVariable? If so, add a sample input that
     * demonstrates it.
     */
    if (subtype.hasAnnotation(minusNull)) {
      return true;
    }

    if (isUnionNullOrEquivalent(subtype)) {
      return false;
    }

    if (supertypeMatcher.test(subtype.getUnderlyingType())) {
      return true;
    }
    for (AnnotatedTypeMirror supertype : getUpperBounds(subtype)) {
      if (nullnessEstablishingPathExists(supertype, supertypeMatcher)) {
        return true;
      }
    }
    return false;
  }

  private List<? extends AnnotatedTypeMirror> getUpperBounds(AnnotatedTypeMirror type) {
    /*
     * In the case of a type-variable usage, we ignore the bounds attached to it in favor of the
     * bounds on the type-parameter declaration. This is necessary in certain cases.
     *
     * I won't claim to understand *why* it will be necessary. Maybe the bounds aren't getting
     * copied from the declaration to the usage correctly. Or maybe they're getting copied but then
     * CF is mutating/replacing them (since it seems to update *bounds* based on annotations on the
     * *usage* in some cases -- though we've tried to short-circuit that).
     *
     * In any case, in our model for nullness checking, we always want to look at the original
     * bounds. So whatever the reason we have different bounds here, we don't want them.
     *
     * My only worry is that I always worry about making calls to getAnnotatedType, as discussed in
     * various comments in this file (e.g., in NullSpecTreeAnnotator.visitMethodInvocation).
     */
    if (type instanceof AnnotatedTypeVariable
        && !isCapturedTypeVariable(type.getUnderlyingType())) {
      AnnotatedTypeVariable variable = (AnnotatedTypeVariable) type;
      type = getAnnotatedType(variable.getUnderlyingType().asElement());
    }

    switch (type.getKind()) {
      case INTERSECTION:
        return ((AnnotatedIntersectionType) type).getBounds();

      case TYPEVAR:
        return singletonList(((AnnotatedTypeVariable) type).getUpperBound());

        /*
         * We used to have a case here for WILDCARD. It shouldn't be necessary now that we've merged
         * the CF implementation capture conversion. That said, we shouldn't need wildcard handling
         * in isNullnessSubtype, either, and yet we do.
         *
         * So we could consider restoring wildcard handling here, too. But for now, we've left it
         * out, since its implementation required some digging into javac internal types and calling
         * getAnnotatedType (always a little scary, as discussed in
         * NullSpecTreeAnnotator.visitMethodInvocation and elsewhere).
         */

      default:
        return emptyList();
    }
  }

  /*
   * TODO(cpovirk): Consider inlining this; it differs subtly from the similar-sounding check in
   * isNullInclusiveUnderEveryParameterization.
   */
  private boolean isUnionNullOrEquivalent(AnnotatedTypeMirror type) {
    return type.hasAnnotation(unionNull)
        || (isLeastConvenientWorld && type.hasAnnotation(nullnessOperatorUnspecified));
  }

  private final class NullSpecEqualityComparer extends StructuralEqualityComparer {
    NullSpecEqualityComparer(StructuralEqualityVisitHistory typeargVisitHistory) {
      super(typeargVisitHistory);
    }

    @Override
    protected boolean checkOrAreEqual(AnnotatedTypeMirror type1, AnnotatedTypeMirror type2) {
      Boolean pastResult = visitHistory.get(type1, type2, /*hierarchy=*/ unionNull);
      if (pastResult != null) {
        return pastResult;
      }

      boolean result = areEqual(type1, type2);
      this.visitHistory.put(type1, type2, /*hierarchy=*/ unionNull, result);
      return result;
    }

    @Override
    public boolean areEqualInHierarchy(
        AnnotatedTypeMirror type1, AnnotatedTypeMirror type2, AnnotationMirror top) {
      return areEqual(type1, type2);
    }

    private boolean areEqual(AnnotatedTypeMirror type1, AnnotatedTypeMirror type2) {
      /*
       * Eventually, we'll test the spec definition: "type1 is a subtype of type2 and vice versa."
       * However, we perform some other tests first. Why?
       *
       * Well, originally, testing the spec definition somehow produced infinite recursion. (I don't
       * think the spec requires infinite recursion; I think the implementation just produced it
       * somehow, though I don't recall how.) When that happened, I switched to not using the spec
       * definition _at all_. However, I don't see infinite recursion anymore (with any of our
       * samples or with Guava), even if I use that definition exclusively. I remain a _little_
       * nervous about switching to using the spec definition exclusively -- or even using it
       * conditionally, as we do now -- but it's _probably_ safe.
       *
       * But we still can't rely solely on the spec definition, at least at present. If we try to,
       * we produce errors for samples like the following:
       *
       * https://github.com/jspecify/jspecify/blob/5f67b5e2388adc6e1ce386bf7957eef588d981db/samples/OverrideParametersThatAreTypeVariables.java#L41
       *
       * I think that is because of the somewhat odd contract for this method, as described into the
       * TODO at the end of this method: The method is not actually _supposed_ to check that the
       * given _types_ are equal, only that... their primary annotations are? Or something?
       *
       * TODO(cpovirk): Even if we're keeping both checks, it seems like _some_ of the code below
       * may be redundant (or even wrong).
       */
      boolean type1IsUnspecified = type1.hasAnnotation(nullnessOperatorUnspecified);
      boolean type2IsUnspecified = type2.hasAnnotation(nullnessOperatorUnspecified);
      boolean bothAreUnspecified = type1IsUnspecified && type2IsUnspecified;
      boolean eitherIsUnspecified = type1IsUnspecified || type2IsUnspecified;
      if (isLeastConvenientWorld && bothAreUnspecified) {
        return false;
      }
      if (!isLeastConvenientWorld && eitherIsUnspecified) {
        return true;
      }
      AnnotationMirror a1 = type1.getAnnotationInHierarchy(unionNull);
      AnnotationMirror a2 = type2.getAnnotationInHierarchy(unionNull);
      if (a1 == a2) {
        return true;
      }
      if (a1 != null && a2 != null && areSame(a1, a2)) {
        return true;
      }
      if (withLeastConvenientWorld().isNullExclusiveUnderEveryParameterization(type1)
          && withLeastConvenientWorld().isNullExclusiveUnderEveryParameterization(type2)) {
        /*
         * One is `T`, and the other is `@MinusNull T`, and `T` has a non-nullable bound. Thus, the
         * two are effectively the same.
         *
         * TODO(cpovirk): Why do we sometimes end up with one of those and sometimes with the other?
         * Can we ensure that we always end up with `@MinusNull T`? Alternatively, can we avoid
         * creating `@MinusNull T` when `T` is already known to be non-nullable? And whether we
         * leave this code in or replace it with other code, do we need to update our subtyping
         * rules to reflect this case?
         */
        return true;
      }
      return getTypeHierarchy().isSubtype(type1, type2)
          && getTypeHierarchy().isSubtype(type2, type1);
      /*
       * TODO(cpovirk): Do we care about the base type, or is looking at annotations enough?
       * super.visitDeclared_Declared has a TODO with a similar question. Err, presumably normal
       * Java type-checking has done that job. A more interesting question may be why we don't look
       * at type args. The answer might be simply: "That's the contract, even though it is
       * surprising, given the names of the class and its methods." (Granted, the docs of
       * super.visitDeclared_Declared also say that it checks that "The types are of the same
       * class/interfaces," so the contract isn't completely clear.)
       */
    }
  }

  @Override
  protected TypeVariableSubstitutor createTypeVariableSubstitutor() {
    return new NullSpecTypeVariableSubstitutor();
  }

  private final class NullSpecTypeVariableSubstitutor extends TypeVariableSubstitutor {
    @Override
    protected AnnotatedTypeMirror substituteTypeVariable(
        AnnotatedTypeMirror argument, AnnotatedTypeVariable use) {
      AnnotatedTypeMirror substitute = argument.deepCopy(/*copyAnnotations=*/ true);

      /*
       * The isNullExclusiveUnderEveryParameterization check handles cases like
       * `ImmutableList.Builder<E>` in non-null-aware code: Technically, we aren't sure if the
       * non-null-aware class might be instantiated with a nullable argument for E. But we know
       * that, no matter what, if someone calls `listBuilder.add(null)`, that is bad. So we treat
       * the declaration as if it said `ImmutableList.Builder<@MinusNull E>`.
       */
      if (withLeastConvenientWorld().isNullExclusiveUnderEveryParameterization(use)) {
        substitute.replaceAnnotation(minusNull);
      } else if (argument.hasAnnotation(unionNull) || use.hasAnnotation(unionNull)) {
        substitute.replaceAnnotation(unionNull);
      } else if (argument.hasAnnotation(nullnessOperatorUnspecified)
          || use.hasAnnotation(nullnessOperatorUnspecified)) {
        substitute.replaceAnnotation(nullnessOperatorUnspecified);
      }

      return substitute;
    }
  }

  @Override
  public NullSpecTransfer createFlowTransferFunction(
      CFAbstractAnalysis<CFValue, NullSpecStore, NullSpecTransfer> analysis) {
    return new NullSpecTransfer(analysis, util);
  }

  @Override
  public AnnotatedDeclaredType getSelfType(Tree tree) {
    AnnotatedDeclaredType superResult = super.getSelfType(tree);
    return superResult == null ? null : withMinusNull(superResult);
  }

  @Override
  protected void addCheckedStandardDefaults(QualifierDefaults defs) {
    /*
     * Override to *not* call the supermethod. The supermethod would set up CLIMB defaults, which we
     * don't want.
     *
     * Furthermore, while we do want defaults of our own, we don't set them here. In particular, we
     * don't call defs.addCheckedCodeDefault(nullnessOperatorUnspecified, OTHERWISE). If we did,
     * then that default would be applied to all unannotated type-variable usages, even in
     * null-aware code. That's because there are multiple rounds of defaulting, 2 of which interact
     * badly:
     *
     * The first round of the 2 to run is per-element defaulting. That includes our null-aware
     * defaulting logic. (See populateNewDefaults below.) That defaulting logic would leave
     * type-variable usages unannotated.
     *
     * The later round to run is checkedCodeDefaults (which would include any defaults set by this
     * method). That logic would find the type-variable usages unannotated. So, if that logic put
     * nullnessOperatorUnspecified on all unannotated type-variable usages, it would really put it
     * on *all* unannotated type-variable usages -- even the ones in null-aware code.
     *
     * To avoid that, we set *all* defaults as per-element defaults. That setup eliminates the
     * second round entirely. Thus, defaulting runs a single time for a given type usage. So, when
     * the null-aware logic declines to annotate a type-variable usage, it stays unannotated
     * afterward.
     *
     * The stock CF does not have this problem because it hass no such thing as a per-element
     * default of "do not annotate this" that overrides a checkedCodeDefaults default of "do
     * annotate this."
     */
  }

  @Override
  protected void checkForDefaultQualifierInHierarchy(QualifierDefaults defs) {
    /*
     * We don't set normal checkedCodeDefaults. This method would report that lack of defaults as a
     * problem. That's because CF wants to ensure that every[*] type usage is annotated.
     *
     * However, we *do* ensure that every[*] type usage is annotated. To do so, we always set a
     * default for OTHERWISE on top-level elements. (We do this in populateNewDefaults.) See further
     * discussion in addCheckedStandardDefaults.
     *
     * So, we override this method to not report a problem.
     *
     * [*] There are a few exceptions that we don't need to get into here.
     */
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
    protected void populateNewDefaults(Element elt, boolean initialDefaultsAreEmpty) {
      /*
       * Note: This method does not contain the totality of our defaulting logic. For example, our
       * TypeAnnotator has special logic for upper bounds _in the case of `super` wildcards
       * specifically_.
       *
       * Note: Setting a default here affects not only this element but also its descendants in the
       * syntax tree.
       */
      if (hasNullMarkedOrEquivalent(elt)) {
        addElementDefault(elt, unionNull, UNBOUNDED_WILDCARD_UPPER_BOUND);
        addElementDefault(elt, minusNull, OTHERWISE);
        addDefaultToTopForLocationsRefinedByDataflow(elt);
        /*
         * (For any TypeUseLocation that we don't set an explicit value for, we inherit any value
         * from the enclosing element, which might be a non-null-aware element. That's fine: While
         * our non-null-aware setup sets defaults for more locations than just these, it sets those
         * locations' defaults to minusNull -- matching the value that we want here.)
         */
      } else if (initialDefaultsAreEmpty) {
        /*
         * We need to set defaults appropriate to non-null-aware code. In a normal checker, we would
         * expect for such "default defaults" to be set in addCheckedStandardDefaults. But we do
         * not, as discussed in our implementation of that method.
         */

        // Here's the big default, the "default default":
        addElementDefault(elt, nullnessOperatorUnspecified, OTHERWISE);

        // Some locations are intrinsically non-nullable:
        addElementDefault(elt, minusNull, CONSTRUCTOR_RESULT);
        addElementDefault(elt, minusNull, RECEIVER);

        // We do want *some* of the CLIMB standard defaults:
        addDefaultToTopForLocationsRefinedByDataflow(elt);
        addElementDefault(elt, minusNull, IMPLICIT_LOWER_BOUND);

        /*
         * But note one difference from the CLIMB defaults: We want the default for implicit upper
         * bounds to match the "default default" of nullnessOperatorUnspecified, not to be
         * top/unionNull. We accomplished this already simply by not making our
         * addCheckedStandardDefaults implementation call its supermethod (which would otherwise
         * call addClimbStandardDefaults, which would override the "default default").
         */
      }
    }

    private void addDefaultToTopForLocationsRefinedByDataflow(Element elt) {
      for (TypeUseLocation location : IMPLEMENTATION_VARIABLE_LOCATIONS) {
        /*
         * Handling exception parameters correctly is hard, so just treat them as if they're
         * restricted to non-null values. Of course the caught exception is already non-null, so all
         * this does is forbid users from manually assigning null to an exception parameter.
         */
        if (location == EXCEPTION_PARAMETER) {
          addElementDefault(elt, minusNull, location);
        } else {
          addElementDefault(elt, unionNull, location);
        }
      }
    }

    @Override
    protected boolean shouldAnnotateOtherwiseNonDefaultableTypeVariable(AnnotationMirror qual) {
      /*
       * CF usually doesn't apply defaults to type-variable usages. But in non-null-aware code, we
       * want our default of nullnessOperatorUnspecified to apply even to type variables.
       *
       * But there are 2 other things to keep in mind:
       *
       * - CF *does* apply defaults to type-variable usages *if* they are local variables. That's
       * because it will refine their types with dataflow. This CF behavior works fine for us: Since
       * we want to apply defaults in strictly more cases, we're happy to accept what CF already
       * does for local variables. (We do need to be sure to apply unionNull (our top type) in that
       * case, rather than nullnessOperatorUnspecified. We accomplish that in
       * addDefaultToTopForLocationsRefinedByDataflow.)
       *
       * - Non-null-aware code (discussed above) is easy: We apply nullnessOperatorUnspecified to
       * everything except local variables. But null-aware code more complex. First, set aside local
       * variables, which we handle as discussed above. After that, we need to apply minusNull to
       * most types, but we need to *not* apply it to (non-local-variable) type-variable usages.
       * (For more on this, see isNullExclusiveUnderEveryParameterization.) This need is weird
       * enough that stock CF doesn't appear to support it. Our solution is to introduce this hook
       * method into our CF fork and then override it here. Our solution also requires that we set
       * up defaulting in a non-standard way, as discussed in addCheckedStandardDefaults and other
       * locations.
       */
      return areSame(qual, nullnessOperatorUnspecified);
    }

    @Override
    public boolean applyConservativeDefaults(Element annotationScope) {
      /*
       * Ignore any command-line flag to request conservative defaults. The principle of
       * "unspecified nullness" is that we configure conservatism/leniency through changes in our
       * subtyping rules, rather than changes in how we choose the default annotation / nullness
       * operator of any type.
       */
      return false;
    }
  }

  @Override
  protected void addComputedTypeAnnotations(Tree tree, AnnotatedTypeMirror type, boolean iUseFlow) {
    super.addComputedTypeAnnotations(
        tree,
        type,
        iUseFlow
            /*
             * TODO(cpovirk): Eliminate this workaround (which may cause problems of its own). But
             * currently, it helps in some of our samples and in Guava, though I am unsure why. The
             * problem it works around may well be caused by our failure to keep wildcards'
             * annotations in sync with their bounds' annotations (whereas stock CF does).
             *
             * Note that the `type.getKind() != WILDCARD` check appears to be necessary before the
             * CF implementation of capture conversion and unnecessary afterward, while the reverse
             * is true of the `isCapturedTypeVariable` check.
             */
            && type.getKind() != WILDCARD
            && !isCapturedTypeVariable(type.getUnderlyingType())
            /*
             * TODO(cpovirk): See if we can remove this workaround after merging the fix for
             * https://github.com/typetools/checker-framework/issues/5042.
             *
             * The workaround becomes necessary after the CF implementation of capture conversion.
             * Without it, we see dataflow decide that `b ? null : nullable` has a type of
             * _non-null_, as in code like the following:
             *
             * https://github.com/google/guava/blob/156694066b5198740a820c6eef723fb86c054343/guava/src/com/google/common/base/Throwables.java#L470
             *
             * (But I haven't been able to reproduce this in a smaller test.)
             *
             * Fortunately, I think the workaround is harmless:
             * TypeFromExpressionVisitor.visitConditionalExpression calls getAnnotatedType on both
             * candidate expressions, and getAnnotatedType applies dataflow. So the ternary should
             * end up with the dataflow-correct result by virtue of applying lub to those types.
             *
             * (I think the only exception would be if someone performed a null check _on an entire
             * ternary_ and then expected _another appearance of that same ternary_ to be recognized
             * as non-null. That seems implausible.)
             *
             * (Still, it would be good to look into what's going on here in case it's a sign of a
             * deeper problem.)
             */
            && tree.getKind() != CONDITIONAL_EXPRESSION);
  }

  @Override
  protected TypeAnnotator createTypeAnnotator() {
    /*
     * Override to:
     *
     * - write some defaults that are difficult to express with the addCheckedCodeDefault and
     * addElementDefault APIs. But beware: Using TypeAnnotator for this purpose is safe only for
     * defaults that are common to null-aware and non-null-aware code!
     *
     * - *not* do what the supermethod does. I don't fully understand what the supermethod's
     * PropagationTypeAnnotator does, but here's part of what I think it does: It overwrites the
     * annotations on upper bounds of unbounded wildcards to match those on their corresponding type
     * parameters. This means that it overwrites our not-null-aware default bound of
     * @NullnessUnspecified. But I also seem to be seeing problems in the *reverse* direction, and I
     * understand those even less. (To be fair, our entire handling of upper bounds of unbounded
     * wildcards is a hack: The normal CF quite reasonably doesn't want for them to have bounds of
     * their own, but we do.) Sadly, it turns out that the supermethod's effects are sometimes
     * *desirable*, so this workaround causes issues of its own....
     */
    return new NullSpecTypeAnnotator(this);
  }

  private final class NullSpecTypeAnnotator extends TypeAnnotator {
    NullSpecTypeAnnotator(AnnotatedTypeFactory typeFactory) {
      super(typeFactory);
    }

    @Override
    public Void visitDeclared(AnnotatedDeclaredType type, Void p) {
      AnnotatedDeclaredType enclosingType = type.getEnclosingType();
      if (enclosingType != null) {
        /*
         * TODO(cpovirk): If NullSpecVisitor starts looking at source trees instead of the derived
         * AnnotatedTypeMirror objects, then change this code to fill in this value unconditionally
         * (matching visitPrimitive below).
         */
        addIfNoAnnotationPresent(enclosingType, minusNull);
      }
      return super.visitDeclared(type, p);
    }

    @Override
    public Void visitPrimitive(AnnotatedPrimitiveType type, Void p) {
      type.replaceAnnotation(minusNull);
      return super.visitPrimitive(type, p);
    }

    @Override
    public Void visitWildcard(AnnotatedWildcardType type, Void p) {
      if (type.getUnderlyingType().getSuperBound() != null) {
        addIfNoAnnotationPresent(type.getExtendsBound(), unionNull);
      }
      return super.visitWildcard(type, p);
    }
  }

  @Override
  protected void adaptGetClassReturnTypeToReceiver(
      AnnotatedExecutableType getClassType, AnnotatedTypeMirror receiverType, ExpressionTree tree) {
    super.adaptGetClassReturnTypeToReceiver(getClassType, receiverType, tree);

    /*
     * Change `Class<? super Foo?>` to `Class<? super Foo>`.
     *
     * We could instead do this in NullSpecTreeAnnotator.visitMethodInvocation. However, by handling
     * it here, we cover not only method invocations but also method references.
     *
     * TODO(cpovirk): Move other logic out of NullSpecTreeAnnotator.visitMethodInvocation and into
     * methodFromUse so that it, too, can cover method references?
     */
    AnnotatedDeclaredType returnType = (AnnotatedDeclaredType) getClassType.getReturnType();
    AnnotatedTypeVariable typeArg = (AnnotatedTypeVariable) returnType.getTypeArguments().get(0);
    typeArg.getUpperBound().replaceAnnotation(minusNull);
  }

  @Override
  protected TreeAnnotator createTreeAnnotator() {
    return new ListTreeAnnotator(new NullSpecTreeAnnotator(this), super.createTreeAnnotator());
  }

  // TODO(cpovirk): Promote this to a top-level class.
  private final class NullSpecTreeAnnotator extends TreeAnnotator {
    NullSpecTreeAnnotator(AnnotatedTypeFactory typeFactory) {
      super(typeFactory);
    }

    @Override
    public Void visitLiteral(LiteralTree tree, AnnotatedTypeMirror type) {
      if (tree.getKind().asInterface() == LiteralTree.class) {
        type.addAnnotation(tree.getKind() == NULL_LITERAL ? unionNull : minusNull);
      }

      return super.visitLiteral(tree, type);
    }

    @Override
    public Void visitBinary(BinaryTree tree, AnnotatedTypeMirror type) {
      type.addAnnotation(minusNull);
      return super.visitBinary(tree, type);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree tree, AnnotatedTypeMirror type) {
      ExecutableElement method = elementFromUse(tree);

      if (establishesStreamElementsAreNonNull(tree)) {
        AnnotatedTypeMirror returnedStreamElementType =
            ((AnnotatedDeclaredType) type).getTypeArguments().get(0);
        returnedStreamElementType.replaceAnnotation(minusNull);
      }

      /*
       * Unsoundly assume that copyOf and copyOfRange return an array of non-null elements. This is
       * doubly unsound:
       *
       * - Both methods permit creating a larger array than the original -- and padding the result
       * with nulls. That said, this is no worse a problem than we have with array initialization in
       * general: We already allow users to write `Object[] o = new Object[10];`, and that has the
       * same problem. Not to mention that we further treat arrays as covariant, so it's always
       * possible to "break" an `Object[]` by assigning it to a `@Nullable Object[]` and writing
       * null into it.
       *
       * - Even if the *input* array *already* has an element type that includes null, we *still*
       * claim that the output array's element type does not. This could probably be fixed
       * relatively easily, but: First, I'm always at least a little nervous about calling
       * getAnnotatedType (or even the more lightweight fromExpression), both because I worry
       * specifically that it might call back into this method (infinite recursion?) and generally
       * that it might be expensive. Second, I imagine that a call to copyOf or copyOfRange is often
       * used to "trim" uninitialized elements from the end of an array. Thus, we'd like to make
       * the use of those methods a pleasant experience so as to encourage users to be "honest"
       * about having a `@Nullable Object[]` at the beginning, which they convert to an `Object[]`
       * only later.
       *
       * Still, we may want to revisit all this.
       */
      if (nameMatches(method, "Arrays", "copyOf") || nameMatches(method, "Arrays", "copyOfRange")) {
        ((AnnotatedArrayType) type).getComponentType().replaceAnnotation(minusNull);
      }

      /*
       * If this is a call to `Collection.toArray()`, then try to produce a more specific return
       * type than `@Nullable Object[]`.
       *
       * *Ideally* we would do this not just for method invocations but also for method references
       * and even other lookups of methods, like checking whether MyList.toArray is a valid override
       * of Collection.toArray. (That is, if MyList is restricted to non-null types, then
       * MyList.toArray should not declare a return type of `@Nullable Object[]`.)
       */
      AnnotationMirror upperBoundOnArrayElementType = upperBoundOnToArrayElementType(tree);
      if (upperBoundOnArrayElementType != null) {
        ((AnnotatedArrayType) type)
            .getComponentType()
            .replaceAnnotation(upperBoundOnArrayElementType);
      }

      if (isGetCauseOnExecutionException(tree)) {
        /*
         * ExecutionException.getCause() *can* in fact return null. In fact, the JDK even has
         * methods that can produce such an exception:
         * http://gee.cs.oswego.edu/cgi-bin/viewcvs.cgi/jsr166/src/main/java/util/concurrent/AbstractExecutorService.java?revision=1.54&view=markup#l185
         *
         * So the right way to annotate the method is indeed to mark is @Nullable. (Aside: When I
         * first wrote this comment, a declaration of ExecutionException.getCause() in a stub file
         * would have had no effect, since that override of Throwable.getCause() does not exist in
         * the JDK. However, I'm now updating this comment to say that such declarations may have
         * started working with version 3.11.0, thanks to
         * https://github.com/typetools/checker-framework/pull/4275. But I haven't tested.)
         *
         * Still, in practice, the nullness errors we've reported when people dereference
         * ExecutionException.getCause() have not been finding real issues. So, for the moment,
         * we'll pretend that the value returned by that method is never null.
         *
         * TODO(cpovirk): Revisit this once we offer ways to suppress errors that are less noisy and
         * more automated. Even before then, consider reducing the scope of this exception to apply
         * only to exceptions thrown by Future.get, which, unlike those thrown by
         * ExecutorService.invokeAny, do have a cause in all real-world implementations I'm aware
         * of.
         *
         * TODO(cpovirk): Also, consider banning calls to ExecutionException constructors that pass
         * a nullable argument or call an overload that does not require a cause.
         */
        type.replaceAnnotation(minusNull);
      }

      if (isGetCauseOnInvocationTargetException(tree)) {
        /*
         * InvocationTargetException.getCause() is similar to ExecutionException.getCause(),
         * discussed above. At least with InvocationTargetException, I am not aware of any JDK
         * methods that produce an instance with a null cause.
         *
         * TODO(cpovirk): Still, consider being more conservative, as with ExecutionException.
         */
        type.replaceAnnotation(minusNull);
      }

      if (isGetEnumConstantsOnEnumClass(tree)) {
        /*
         * This is not *completely* sound: getEnumConstants() on a Class<? extends Enum> can return
         * null in at least two cases:
         *
         * - java.lang.Enum.class.getEnumConstants()
         *
         * - getClass().getEnumConstants() from within the body of an enum-value "subclass"
         */
        type.replaceAnnotation(minusNull);
      }

      if (isGetOrDefaultWithNonnullMapValuesAndDefault(tree)) {
        type.replaceAnnotation(minusNull);
      }

      return super.visitMethodInvocation(tree, type);
    }

    private boolean isGetOrDefaultWithNonnullMapValuesAndDefault(MethodInvocationTree tree) {
      if (!util.isOrOverrides(elementFromUse(tree), util.mapGetOrDefaultElement)) {
        return false;
      }
      if (tree.getMethodSelect().getKind() != MEMBER_SELECT) {
        /*
         * We don't care much about handling IDENTIFIER, since we're not likely to be analyzing a
         * Map implementation with a call to getOrDefault on itself, at least not with a
         * defaultValue that is known to be non-null. But see the TODO in
         * upperBoundOnToArrayElementType.
         */
        return false;
      }
      MemberSelectTree methodSelect = (MemberSelectTree) tree.getMethodSelect();
      ExpressionTree receiver = methodSelect.getExpression();
      AnnotatedDeclaredType mapType =
          asSuper(
              NullSpecAnnotatedTypeFactory.this,
              /*
               * For hand-wringing about this call to getAnnotatedType, see
               * upperBoundOnToArrayElementType.
               */
              getAnnotatedType(receiver),
              javaUtilMap);
      AnnotatedTypeMirror valueType =
          ((AnnotatedDeclaredType) applyCaptureConversion(mapType)).getTypeArguments().get(1);
      AnnotatedTypeMirror defaultType = getAnnotatedType(tree.getArguments().get(1));
      return withLeastConvenientWorld().isNullExclusiveUnderEveryParameterization(valueType)
          && withLeastConvenientWorld().isNullExclusiveUnderEveryParameterization(defaultType);
      /*
       * TODO(cpovirk): Also handle the case in which at least one has unspecified nullness, similar
       * to what we do in upperBoundOnToArrayElementType.
       */
    }

    private boolean isGetEnumConstantsOnEnumClass(MethodInvocationTree tree) {
      if (elementFromUse(tree) != util.classGetEnumConstantsElement) {
        return false;
      }
      if (tree.getMethodSelect().getKind() != MEMBER_SELECT) {
        /*
         * We don't care much about handling IDENTIFIER, since we're not likely to be analyzing
         * Class itself. But see the TODO in upperBoundOnToArrayElementType.
         */
        return false;
      }
      MemberSelectTree methodSelect = (MemberSelectTree) tree.getMethodSelect();
      TypeMirror receiverType = typeOf(methodSelect.getExpression());
      return types.isSubtype(receiverType, util.javaLangClassOfExtendsEnum);
    }

    private boolean isGetCauseOnExecutionException(MethodInvocationTree tree) {
      if (tree.getMethodSelect().getKind() != MEMBER_SELECT) {
        /*
         * We don't care much about handling IDENTIFIER, since we're not likely to be analyzing
         * ExecutionException itself (nor a subclass of it). But see the TODO in
         * upperBoundOnToArrayElementType.
         */
        return false;
      }
      MemberSelectTree methodSelect = (MemberSelectTree) tree.getMethodSelect();

      /*
       * We can't use nameMatches(ExecutionException, getCause) because the ExecutableElement of the
       * call is that of Throwable.getCause, not ExecutionException.getCause (an override that does
       * not exist in the JDK).
       *
       * (But using TypeMirror is technically superior: It checks the whole class name, and it could
       * be extended to look for subtypes. Ideally we'd also replace the method-name check with a
       * full-on override check.)
       */
      return isAnyOf(
              typeOf(methodSelect.getExpression()),
              util.javaUtilConcurrentExecutionException,
              /*
               * TODO(cpovirk): For UncheckedExecutionException, rather than have a special case
               * here, we can edit the class definition itself. That is, we edit it to override
               * getCause() to declare the return type we want. And, to make that actually safe, we
               * would likely also want to annotate its constructor to require a non-null exception
               * parameter.
               *
               * However, there are at least two complications:
               *
               * 1. If we annotate the constructor parameter, that would also affect existing users
               * of stock CF. In particular, it would make `new
               * UncheckedExecutionException(executionException.getCause())` an error, since stock
               * CF does not share our unsound assumption that executionException.getCause() returns
               * non-null. We could reduce the fallout from this by introducing an internal stub for
               * UncheckedExecutionException (which we would enable only for users of stock CF). For
               * that matter, we could introduce an internal (unsound) stub for ExecutionException
               * itself (though that may not be a complete solution, since users may be passing a
               * nullable cause that they got from any number of places). Or we could always just
               * suppress the new errors we create. *Or* we can change getCause) but unsoundly leave
               * the constructor parameter as @Nullable.
               *
               * 2. UncheckedExecutionException has constructors that do not even accept a cause.
               * Therefore, if we want to guarantee that its getCause() method never returns null,
               * then we need to ban all use of those constructors (or at least use that isn't
               * followed by a call to initCause()). Such a ban would likely be implemented as a
               * checker-specific behavior. That argues for leaving UncheckedExecutionException
               * annotated with nullable types and adding in the non-null behavior in our specific
               * checker.
               *
               * This deserves more thought, especially given the potential impact on external
               * users.
               */
              util.uncheckedExecutionException)
          && methodSelect.getIdentifier().contentEquals("getCause");
    }

    private boolean isAnyOf(TypeMirror actual, TypeMirror a, TypeMirror b) {
      Types typeUtils = analysis.getTypes();
      /*
       * TODO(cpovirk): Eliminate null permissiveness by accepting a Collection<TypeMirror> and just
       * not inserting types into it unless they're available on this compilation's classpath.
       */
      return (a != null && typeUtils.isSameType(actual, a))
          || (b != null && typeUtils.isSameType(actual, b));
    }

    private boolean isGetCauseOnInvocationTargetException(MethodInvocationTree tree) {
      ExecutableElement method = elementFromUse(tree);
      return nameMatches(method, "InvocationTargetException", "getCause")
          || nameMatches(method, "InvocationTargetException", "getTargetException");
    }

    private AnnotationMirror upperBoundOnToArrayElementType(MethodInvocationTree tree) {
      ExecutableElement method = elementFromUse(tree);
      if (!util.isOrOverrides(method, util.collectionToArrayNoArgElement)) {
        return null;
      }

      /*
       * TODO(cpovirk): Consider using this code in other methods above (which currently handle only
       * MEMBER_SELECT). And consider changing *this* code to use
       * AnnotatedTypeFactory.getReceiverType, since we need a full-on AnnotatedTypeMirror here. No,
       * getReceiverType doesn't eliminate the getAnnotatedType call that I worry about below. But
       * it could save us some code and probably handle more cases than we do. Arguably that extra
       * handling is enough reason to use getReceiverType even when we could get by with a plain
       * TypeMirror, as in isGetCauseOnExecutionException.
       */
      Tree receiver;
      if (tree.getMethodSelect().getKind() == MEMBER_SELECT) {
        receiver = ((MemberSelectTree) tree.getMethodSelect()).getExpression();
      } else if (tree.getMethodSelect().getKind() == IDENTIFIER) {
        /*
         * TODO(cpovirk): We need to figure out whether the call is being made on the instance of
         * the enclosing class or on another class that encloses that.
         */
        receiver = enclosingClass(getPath(tree));
      } else {
        // TODO(cpovirk): Can this happen? Maybe throw an exception?
        return null;
      }

      AnnotatedDeclaredType collectionType =
          asSuper(
              NullSpecAnnotatedTypeFactory.this,
              /*
               * As discussed in visitMethodInvocation, I am nervous about calling getAnnotatedType
               * from here. (But for what it's worth, a quick test on com.google.common.primitives
               * suggests no performance impact.)
               *
               * If we run into problems, we could explore simpler alternatives. In
               * particular, if the problem is dataflow, we could consider creating a second
               * instance of NullSpecAnnotatedTypeFactory that passes useFlow=false, and then we
               * could use that here. That should be safe: We don't need dataflow because we care
               * only about the _type argument_.
               *
               * (OK, now I'm imagining a scenario in which the Checker Framework infers the type
               * argument to `new MyList<>(delegate) { ... }` based on dataflow analysis of the
               * `delegate` variable :) But I don't even know if it would take dataflow into account
               * there. And if does, I could live with not handling that edge case :))
               */
              applyCaptureConversion(getAnnotatedType(receiver)),
              javaUtilCollection);
      AnnotatedTypeMirror elementType = collectionType.getTypeArguments().get(0);
      if (withLeastConvenientWorld().isNullExclusiveUnderEveryParameterization(elementType)) {
        return minusNull;
      } else if (withMostConvenientWorld().isNullExclusiveUnderEveryParameterization(elementType)) {
        return nullnessOperatorUnspecified;
      }
      /*
       * We could `return unionNull`, but there's no need because the type was already set to that
       * from the declaration `@Nullable Object[] toArray` in the annotated JDK.
       *
       * (And there's no harm in forcing the caller to perform a null check: The caller already
       * needs to make its call to getComponentType().replaceAnnotation(...) a conditional call
       * because the caller doesn't know if the method invocation was a call to toArray() or not.)
       *
       * Conceivably, the compiler might even see the method invocation as a call to
       * `MyList.toArray()`, where `MyList.toArray()` is an override that declares a more specific
       * return type than the default `@Nullable Object[]`. Then it would be odd for us to overwrite
       * that with a the more general type. To be fair, we would do that only in the case in which
       * MyList was incorrectly annotated: If it were annotated correctly, then it would restrict
       * its element type to non-null values, and thus we'd return minusNull here.
       *
       * OK, fine: Suppose that `MyList.toArray()` declares a return type of `Object[]` but leaves
       * *unspecified* whether its element type includes null. (This would be an incorrect (or
       * rather "incomplete") declaration but one that we would let through in "lenient mode.")
       * Then, when someone calls that method, we would overwrite its declared return type with
       * `@NullnessUnspecified Object[]`. That's technically wrong, and what we technically *should*
       * do is probably:
       *
       * TODO(cpovirk): Have visitMethodInvocation set the array element type to the least upper
       * bound of the type we return and the type it already has. And add sample inputs to test.
       */
      return null;
    }

    private boolean establishesStreamElementsAreNonNull(MethodInvocationTree invocation) {
      ExecutableElement method = elementFromUse(invocation);
      if (!nameMatches(method, "Stream", "filter")) {
        return false;
      }
      ExpressionTree predicate = invocation.getArguments().get(0);
      if (predicate instanceof MemberReferenceTree) {
        MemberReferenceTree memberReferenceTree = (MemberReferenceTree) predicate;
        /*
         * TODO(cpovirk): Ensure that it's java.lang.Class.isInstance or java.util.Objects.nonNull
         * specifically.
         */
        return memberReferenceTree.getName().contentEquals("isInstance")
            || memberReferenceTree.getName().contentEquals("nonNull");
      } else if (predicate instanceof LambdaExpressionTree) {
        LambdaExpressionTree lambdaExpressionTree = (LambdaExpressionTree) predicate;
        if (lambdaExpressionTree.getBody().getKind() == NOT_EQUAL_TO) {
          VariableTree lambdaParameter = lambdaExpressionTree.getParameters().get(0);
          BinaryTree binaryTree = (BinaryTree) lambdaExpressionTree.getBody();
          ExpressionTree left = binaryTree.getLeftOperand();
          ExpressionTree right = binaryTree.getRightOperand();
          return areNullAndLambdaParameter(left, right, lambdaParameter)
              || areNullAndLambdaParameter(right, left, lambdaParameter);
        }
        return false;
      } else {
        return false;
      }
    }

    private boolean areNullAndLambdaParameter(
        ExpressionTree u, ExpressionTree v, VariableTree lambdaParameter) {
      return isNullExpression(u) && elementFromUse(v) == elementFromDeclaration(lambdaParameter);
    }

    @Override
    public Void visitIdentifier(IdentifierTree tree, AnnotatedTypeMirror type) {
      annotateIfEnumConstant(tree, type);

      return super.visitIdentifier(tree, type);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree tree, AnnotatedTypeMirror type) {
      annotateIfEnumConstant(tree, type);

      return super.visitMemberSelect(tree, type);
    }

    private void annotateIfEnumConstant(ExpressionTree tree, AnnotatedTypeMirror type) {
      Element element = elementFromUse(tree);
      if (element != null && element.getKind() == ENUM_CONSTANT) {
        /*
         * Even if it was annotated before, override it. There are 2 cases:
         *
         * 1. The declaration had an annotation on it in source. That will still get reported as an
         * error when we visit the declaration (assuming we're compiling the code with the
         * declaration): Anything we do here affects the *usage* but not the declaration. And we
         * know that the usage isn't really @Nullable/@NullnessUnspecified, even if the author of
         * the declaration said so.
         *
         * 2. The declaration had no annotation on it in source, but it was in non-null-aware code.
         * And consequently, defaults.visit(...), which ran before us, applied a default of
         * nullnessOperatorUnspecified. Again, that default isn't correct, so we override it here.
         */
        type.replaceAnnotation(minusNull);
      }
    }
  }

  @Override
  protected NullSpecAnalysis createFlowAnalysis() {
    return new NullSpecAnalysis(checker, this);
  }

  @Override
  public void addDefaultAnnotations(AnnotatedTypeMirror type) {
    super.addDefaultAnnotations(type);
    /*
     * TODO(cpovirk): Find a better solution than this.
     *
     * The problem I'm working around arises during AnnotatedTypes.leastUpperBound on a
     * JSpecify-annotated variant of this code:
     * https://github.com/google/guava/blob/39aa77fa0e8912d6bfb5cb9a0bc1ed5135747b6f/guava/src/com/google/common/collect/ImmutableMultiset.java#L205
     *
     * CF is unable to infer the right type for `LinkedHashMultiset.create(elements)`: It should
     * infer `LinkedHashMultiset<? extends E>`, but instead, it infers `LinkedHashMultiset<? extends
     * Object>`. As expected, it sets isUninferredTypeArgument. As *not* expected, it gets to
     * AtmLubVisitor.lubTypeArgument with type2Wildcard.extendsBound.lowerBound (a null type)
     * missing its annotation.
     *
     * The part of CF responsible for copying annotations, including those on the extends bound, is
     * AsSuperVisitor.visitWildcard_Wildcard. Under stock CF, copyPrimaryAnnos(from, typevar) "also
     * sets primary annotations _on the bounds_." Under our CF fork, this is not the case, and we
     * end up with an unannotated lower bound on the type-variable usage E (which, again, is itself
     * a bound of a wildcard).
     *
     * (Aside: I haven't looked into how the _upper_ bound of the type-variable usage gets an
     * annotation set on it. Could it be happening "accidentally," and if so, might it be wrong
     * sometimes?)
     *
     * The result of an unannotated lower bound is a crash in NullSpecQualifierHierarchy.isSubtype,
     * which passes null to areSame.
     *
     * The workaround: If we see a type-variable usage whose lower bound is a null type that lacks
     * an annotation, we annotate that bound as non-null. This workaround shouldn't break any
     * working code, but it may or may not be universally the right solution to a missing
     * annotation.
     *
     * I am trying to ignore other questions here, such as:
     *
     * - Would it make more sense to set the lower bound to match the upper bound, as stock CF does?
     * I suspect not under our approach, but I haven't thought about it.
     *
     * - Does trying to pick correct annotations even matter in the context of an uninferred type
     * argument? Does the very idea of "correct annotations" lose meaning in that context?
     *
     * - Should we fix this in AsSuperVisitor instead? Or would it fix itself if we set bounds on
     * our type-variable usages and wildcards in the same way that stock CF does? (Following stock
     * CF would likely save us from other problems, too.)
     *
     * - What's up with the _upper_ bound, as discussed in a parenthetical above?
     */
    if (type instanceof AnnotatedTypeVariable) {
      AnnotatedTypeMirror lowerBound = ((AnnotatedTypeVariable) type).getLowerBound();
      if (lowerBound instanceof AnnotatedNullType
          && !lowerBound.isAnnotatedInHierarchy(unionNull)) {
        lowerBound.addAnnotation(minusNull);
      }
    }
  }

  @Override
  protected AnnotationFormatter createAnnotationFormatter() {
    return new DefaultAnnotationFormatter() {
      @Override
      public String formatAnnotationString(
          Collection<? extends AnnotationMirror> annos, boolean printInvisible) {
        return super.formatAnnotationString(annos, /*printInvisible=*/ false);
      }
    };
  }

  @Override
  protected AnnotatedTypeFormatter createAnnotatedTypeFormatter() {
    return new NullSpecAnnotatedTypeFormatter();
  }

  private final class NullSpecAnnotatedTypeFormatter implements AnnotatedTypeFormatter {
    @Override
    public String format(AnnotatedTypeMirror type) {
      return format(type, /*printVerbose=*/ false);
    }

    @Override
    public String format(AnnotatedTypeMirror type, boolean printVerbose) {
      StringBuilder result = new StringBuilder();
      IdentityHashMap<AnnotatedTypeMirror, Present> visiting = new IdentityHashMap<>();
      new AnnotatedTypeVisitor<Void, Void>() {
        @Override
        public Void visit(AnnotatedTypeMirror type) {
          return visit(type, null);
        }

        @Override
        public Void visit(AnnotatedTypeMirror type, Void aVoid) {
          return type.accept(this, null);
        }

        @Override
        public Void visitDeclared(AnnotatedDeclaredType type, Void aVoid) {
          append(simpleName(type));
          if (!type.getTypeArguments().isEmpty()) {
            append("<");
            visitJoining(type.getTypeArguments(), ", ");
            append(">");
          }
          append(operator(type));
          return null;
        }

        @Override
        public Void visitIntersection(AnnotatedIntersectionType type, Void aVoid) {
          return visitJoining(type.getBounds(), " & ");
        }

        @Override
        public Void visitUnion(AnnotatedUnionType type, Void aVoid) {
          return visitJoining(type.getAlternatives(), " | ");
        }

        @Override
        public Void visitExecutable(AnnotatedExecutableType type, Void aVoid) {
          visit(type.getReturnType());
          append(" ");
          append(type.getElement().getSimpleName());
          append("(");
          visitJoining(type.getParameterTypes(), ", ");
          append(")");
          return null;
        }

        @Override
        public Void visitArray(AnnotatedArrayType type, Void aVoid) {
          visit(type.getComponentType());
          append("[]");
          append(operator(type));
          return null;
        }

        @Override
        public Void visitTypeVariable(AnnotatedTypeVariable type, Void aVoid) {
          if (isCapturedTypeVariable(type.getUnderlyingType())) {
            Present currentlyVisiting = visiting.put(type, Present.INSTANCE);
            if (currentlyVisiting == Present.INSTANCE) {
              append("...");
              return null;
            }

            String operator = operator(type);
            if (!operator.isEmpty()) {
              append("{");
            }
            // TODO(cpovirk): Do we ever need to put braces around just the `? extends Foo` part?
            append(type.getUnderlyingType().toString().replaceFirst(" of [?].*", " of ?"));
            if (hasUpperBound(type)) {
              append(" extends ");
              visit(type.getUpperBound());
            }
            if (hasLowerBound(type)) {
              append(" super ");
              visit(type.getLowerBound());
            }
            if (!operator.isEmpty()) {
              append("}");
            }
            append(operator);

            visiting.remove(type);
          } else {
            append(simpleName(type));
            append(operator(type));
          }
          return null;
        }

        @Override
        public Void visitPrimitive(AnnotatedPrimitiveType type, Void aVoid) {
          append(type.getPrimitiveKind().toString().toLowerCase(Locale.ROOT));
          return null;
        }

        @Override
        public Void visitNoType(AnnotatedNoType type, Void aVoid) {
          append("void");
          return null;
        }

        @Override
        public Void visitNull(AnnotatedNullType type, Void aVoid) {
          append("null");
          append(operator(type));
          return null;
        }

        @Override
        public Void visitWildcard(AnnotatedWildcardType type, Void aVoid) {
          Present currentlyVisiting = visiting.put(type, Present.INSTANCE);
          if (currentlyVisiting == Present.INSTANCE) {
            append("...");
            return null;
          }

          String operator = operator(type);
          if (!operator.isEmpty()) {
            append("{");
          }
          append("?");
          if (hasExtendsBound(type)) {
            append(" extends ");
            visit(type.getExtendsBound());
          }
          if (hasSuperBound(type)) {
            append(" super ");
            visit(type.getSuperBound());
          }
          if (!operator.isEmpty()) {
            append("}");
          }
          append(operator);
          visiting.remove(type);
          return null;
        }

        boolean hasUpperBound(AnnotatedTypeVariable type) {
          return !isUnboundedForExtendsOrUpper(type.getUpperBound());
        }

        boolean hasExtendsBound(AnnotatedWildcardType type) {
          return !isUnboundedForExtendsOrUpper(type.getExtendsBound());
        }

        boolean isUnboundedForExtendsOrUpper(AnnotatedTypeMirror bound) {
          return bound instanceof AnnotatedDeclaredType
              && ((AnnotatedDeclaredType) bound)
                  .getUnderlyingType()
                  .asElement()
                  .getSimpleName()
                  .contentEquals("Object")
              // TODO(cpovirk): Look specifically for java.lang.Object.
              && bound.hasAnnotation(unionNull);
        }

        boolean hasLowerBound(AnnotatedTypeVariable type) {
          return !isUnboundedForSuperOrLower(type.getLowerBound());
        }

        boolean hasSuperBound(AnnotatedWildcardType type) {
          return !isUnboundedForSuperOrLower(type.getSuperBound());
        }

        boolean isUnboundedForSuperOrLower(AnnotatedTypeMirror bound) {
          return bound instanceof AnnotatedNullType
              && !bound.hasAnnotation(unionNull)
              && !bound.hasAnnotation(nullnessOperatorUnspecified);
        }

        Void visitJoining(List<? extends AnnotatedTypeMirror> types, String separator) {
          boolean first = true;
          for (AnnotatedTypeMirror type : types) {
            if (!first) {
              append(separator);
            }
            first = false;
            visit(type);
          }
          return null;
        }

        String operator(AnnotatedTypeMirror type) {
          /*
           * TODO(cpovirk): It would be nice to output "!!" for minusNull "when necessary." But I
           * don't think we have enough information at this point to know whether it's necessary.
           * And it's very rarely necessary (and only occasionally even *helpful*), so I wouldn't
           * want to output it *all* the time.
           *
           * We could try outputting it *except* in the case that T is null-exclusive: In that case,
           * it would be confusing for us to sometimes output `T` and sometimes output `T!!` when
           * the two are equivalent. (See a comment about this in areEqual: "One is `T`, and the
           * other is `@MinusNull T`....")
           *
           * But even that adds noise that we'd probably prefer not to add. And Kotlin gets by
           * without it: At least in my tests, I didn't see `T!!` in Kotlin error messages.
           */
          return type.hasAnnotation(unionNull)
              ? "?"
              : type.hasAnnotation(nullnessOperatorUnspecified) ? "*" : "";
        }

        Name simpleName(AnnotatedDeclaredType type) {
          return type.getUnderlyingType().asElement().getSimpleName();
        }

        Name simpleName(AnnotatedTypeVariable type) {
          return type.getUnderlyingType().asElement().getSimpleName();
        }

        void append(Object o) {
          result.append(o);
        }
      }.visit(type);
      return result.toString();
    }
  }

  @Override
  public void preProcessClassTree(ClassTree tree) {
    // For discussion of short-circuiting, see NullSpecVisitor.processClassTree.
    if (util.hasSuppressWarningsNullness(tree.getModifiers().getAnnotations())) {
      return;
    }

    super.preProcessClassTree(tree);
  }

  @Override
  public void postProcessClassTree(ClassTree tree) {
    /*
     * To avoid writing computed annotations into bytecode (or even into the in-memory javac Element
     * objects), do not call the supermethod.
     *
     * We don't want to write computed annotations to bytecode because we don't want for checkers
     * (including this one!) to depend on those annotations. All core JSpecify nullness information
     * should be derivable from the originally written annotations.
     *
     * (We especially don't want to write @MinusNull to bytecode, since it is an implementation
     * detail of this current checker implementation.)
     *
     * "Computed annotations" includes not only annotations added from defaults but also any
     * @Inherited/@InheritedAnnotation declaration annotations copied from supertypes. We may or may
     * not even want to support inheritance (https://github.com/jspecify/jspecify/issues/155). But
     * even if we do, we wouldn't want to produce different bytecode than a stock compiler, lest
     * tools rely on it.
     *
     * Additionally, when I was letting CF write computed annotations into bytecode, I ran into an
     * type.invalid.conflicting.annos error, which I have described more in
     * https://github.com/jspecify/nullness-checker-for-checker-framework/commit/d16a0231487e239bc94145177de464b5f77c8b19
     */
  }

  @Override
  protected void applyQualifierParameterDefaults(Element elt, AnnotatedTypeMirror type) {
    /*
     * The supermethod implements support for HasQualifierParameter, which we don't use. Still, the
     * supermethod adds 2-3 seconds to the time it takes to check Guava (and perhaps a second to the
     * time it takes to check the samples). So we skip it entirely.
     */
  }

  @Override
  protected void addAnnotationsFromDefaultForType(Element element, AnnotatedTypeMirror type) {
    /*
     * The supermethod implements support for DefaultQualifierForUseTypeAnnotator and
     * DefaultForTypeAnnotator, which we don't use. Still, the supermethod adds ~3 seconds to the
     * time it takes to check Guava (and a fraction of a second to the time it takes to check the
     * samples). So we skip it entirely.
     */
  }

  private void addIfNoAnnotationPresent(AnnotatedTypeMirror type, AnnotationMirror annotation) {
    if (!type.isAnnotatedInHierarchy(unionNull)) {
      type.addAnnotation(annotation);
    }
  }

  /*
   * XXX: When adding support for aliases, make sure to support them here. But consider how to
   * handle @Inherited aliases (https://github.com/jspecify/jspecify/issues/155). In particular, we
   * have already edited getDeclAnnotations to remove its inheritance logic, and we needed to do so
   * to work around another problem (though perhaps we could have found alternatives).
   */
  private boolean hasNullMarkedOrEquivalent(Element elt) {
    return getDeclAnnotations(elt).stream()
            .anyMatch(
                am ->
                    areSameByName(am, "org.jspecify.annotations.NullMarked")
                        || areSameByName(am, "org.jspecify.nullness.NullMarked"))
        /*
         * We assume that ProtoNonnullApi is like NullMarked in that it guarantees that *all* types
         * are non-null, even those that would require type annotations to annotate (e.g.,
         * type-parameter bounds). This is probably a safe assumption, if only because such types
         * might not arise at all in the generated code where ProtoNonnullApi is used.
         */
        || hasAnnotationInCode(elt, "ProtoNonnullApi");
  }

  /**
   * Returns whether the given element has an annotation with the given simple name. This method
   * does not consider stub files.
   */
  private static boolean hasAnnotationInCode(AnnotatedConstruct construct, String name) {
    return construct.getAnnotationMirrors().stream().anyMatch(a -> nameMatches(a, name));
  }

  @SuppressWarnings("unchecked") // safety guaranteed by API docs
  private <T extends AnnotatedTypeMirror> T withMinusNull(T type) {
    // Remove the annotation from the *root* type, but preserve other annotations.
    type = (T) type.deepCopy(/*copyAnnotations=*/ true);
    /*
     * TODO(cpovirk): In the case of a type-variable usage, I feel like we should need to *remove*
     * any existing annotation but then not *add* minusNull. (This is because of the difference
     * between type-variable usages and all other types, as discussed near the end of the giant
     * comment in isNullExclusiveUnderEveryParameterization.) However, the current code passes all
     * tests. Figure out whether that makes sense or we need more tests to show why not.
     */
    type.replaceAnnotation(minusNull);
    return type;
  }

  @SuppressWarnings("unchecked") // safety guaranteed by API docs
  private <T extends AnnotatedTypeMirror> T withUnionNull(T type) {
    // Remove the annotation from the *root* type, but preserve other annotations.
    type = (T) type.deepCopy(/*copyAnnotations=*/ true);
    type.replaceAnnotation(unionNull);
    return type;
  }

  private AnnotatedDeclaredType createType(TypeElement element) {
    return (AnnotatedDeclaredType)
        AnnotatedTypeMirror.createType(element.asType(), this, /*isDeclaration=*/ false);
  }

  // Avoid lambdas so that our Predicates can have a useful toString() for logging purposes.

  enum IsDeclaredOrArray implements Predicate<TypeMirror> {
    IS_DECLARED_OR_ARRAY;

    @Override
    public boolean test(TypeMirror t) {
      return t.getKind() == DECLARED || t.getKind() == ARRAY;
    }
  }

  private Predicate<TypeMirror> isSameTypeAs(TypeMirror target) {
    return new Predicate<>() {
      @Override
      public boolean test(TypeMirror t) {
        return checker.getTypeUtils().isSameType(t, target);
      }

      @Override
      public String toString() {
        return "isSameTypeAs(" + target + ")";
      }
    };
  }

  NullSpecAnnotatedTypeFactory withLeastConvenientWorld() {
    return withLeastConvenientWorld;
  }

  NullSpecAnnotatedTypeFactory withMostConvenientWorld() {
    return withMostConvenientWorld;
  }

  private enum Present {
    INSTANCE;
  }

  private static final List<String> NULLABLE_ANNOTATIONS =
      unmodifiableList(
          asList(
              new String[] {
                "android.annotation.Nullable",
                "android.support.annotation.Nullable",
                "android.support.annotation.RecentlyNullable",
                "androidx.annotation.Nullable",
                "androidx.annotation.RecentlyNullable",
                "com.android.annotations.Nullable",
                "com.beust.jcommander.internal.Nullable",
                "com.google.api.server.spi.config.Nullable",
                "com.google.firebase.database.annotations.Nullable",
                "com.google.firebase.internal.Nullable",
                "com.google.gerrit.common.Nullable",
                "com.google.protobuf.Internal.ProtoMethodAcceptsNullParameter",
                "com.google.protobuf.Internal.ProtoMethodMayReturnNull",
                // TODO(cpovirk): Treat this like a @PolyNull instead:
                "com.google.protobuf.Internal.ProtoPassThroughNullness",
                "com.mongodb.lang.Nullable",
                "com.sun.istack.Nullable",
                "com.sun.istack.internal.Nullable",
                "com.unboundid.util.Nullable",
                "edu.umd.cs.findbugs.annotations.CheckForNull",
                "edu.umd.cs.findbugs.annotations.Nullable",
                "edu.umd.cs.findbugs.annotations.PossiblyNull",
                "edu.umd.cs.findbugs.annotations.UnknownNullnes",
                "io.micrometer.core.lang.Nullable",
                "io.micronaut.core.annotation.Nullable",
                "io.reactivex.annotations.Nullable",
                "io.reactivex.rxjava3.annotations.Nullable",
                "io.vertx.codegen.annotations.Nullable",
                "jakarta.annotation.Nullable",
                "javax.annotation.CheckForNull",
                "javax.annotation.Nullable",
                "jsinterop.annotations.JsNullable",
                "junitparams.converters.Nullable",
                "libcore.util.Nullable",
                "net.bytebuddy.agent.utility.nullability.AlwaysNull",
                "net.bytebuddy.agent.utility.nullability.MaybeNull",
                "net.bytebuddy.utility.nullability.AlwaysNull",
                "net.bytebuddy.utility.nullability.MaybeNull",
                "org.apache.avro.reflect.Nullable",
                "org.apache.cxf.jaxrs.ext.Nullable",
                "org.apache.shindig.common.Nullable",
                "org.checkerframework.checker.nullness.compatqual.NullableDecl",
                "org.checkerframework.checker.nullness.compatqual.NullableType",
                "org.checkerframework.checker.nullness.qual.MonotonicNonNull",
                "org.checkerframework.checker.nullness.qual.Nullable",
                "org.codehaus.commons.nullanalysis.Nullable",
                "org.eclipse.jdt.annotation.Nullable",
                "org.eclipse.jgit.annotations.Nullable",
                "org.jetbrains.annotations.Nullable",
                "org.jmlspecs.annotation.Nullable",
                "org.jspecify.annotations.Nullable",
                "org.jspecify.nullness.Nullable",
                "org.json.Nullable",
                "org.netbeans.api.annotations.common.CheckForNull",
                "org.netbeans.api.annotations.common.NullAllowed",
                "org.netbeans.api.annotations.common.NullUnknown",
                "org.springframework.lang.Nullable",
                "reactor.util.annotation.Nullable",
              }));

  private static final List<String> NOT_NULL_ANNOTATIONS =
      unmodifiableList(
          asList(
              new String[] {
                "android.annotation.NonNull",
                "android.support.annotation.NonNull",
                "androidx.annotation.NonNull",
                "androidx.annotation.RecentlyNonNull",
                "com.android.annotations.NonNull",
                "com.google.firebase.database.annotations.NotNull",
                "com.google.firebase.internal.NonNull",
                "com.sun.istack.NotNull",
                "com.sun.istack.internal.NotNull",
                "com.unboundid.util.NotNull",
                "edu.umd.cs.findbugs.annotations.NonNull",
                "io.micrometer.core.lang.NonNull",
                "io.micronaut.core.annotation.NonNull",
                "io.reactivex.annotations.NonNull",
                "io.reactivex.rxjava3.annotations.NonNull",
                "jakarta.annotation.Nonnull",
                "javax.annotation.Nonnull",
                "javax.validation.constraints.NotNull",
                "jsinterop.annotations.JsNonNull",
                "libcore.util.NonNull",
                "lombok.NonNull",
                "net.bytebuddy.agent.utility.nullability.NeverNull",
                "net.bytebuddy.utility.nullability.NeverNull",
                "org.antlr.v4.runtime.misc.NotNull",
                "org.checkerframework.checker.nullness.compatqual.NonNullDecl",
                "org.checkerframework.checker.nullness.compatqual.NonNullType",
                "org.checkerframework.checker.nullness.qual.NonNull",
                "org.codehaus.commons.nullanalysis.NotNull",
                "org.eclipse.jdt.annotation.NonNull",
                "org.eclipse.jgit.annotations.NonNull",
                "org.eclipse.lsp4j.jsonrpc.validation.NonNull",
                "org.jetbrains.annotations.NotNull",
                "org.jmlspecs.annotation.NonNull",
                "org.jspecify.annotations.NonNull",
                "org.jspecify.nullness.NonNull",
                "org.json.NonNull",
                "org.netbeans.api.annotations.common.NonNull",
                "org.springframework.lang.NonNull",
                "reactor.util.annotation.NonNull",
              }));
}
