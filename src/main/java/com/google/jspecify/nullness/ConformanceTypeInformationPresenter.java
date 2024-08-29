// Copyright 2024 The JSpecify Authors
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

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeFormatter;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.util.visualize.AbstractTypeInformationPresenter;
import org.checkerframework.framework.util.visualize.TypeOccurrenceKind;
import org.checkerframework.javacutil.TreeUtils;

/**
 * Output "sinkType" and "sourceType" diagnostic warning messages so the conformance tests can look
 * for (a subset of) them.
 */
public final class ConformanceTypeInformationPresenter extends AbstractTypeInformationPresenter {

  /**
   * Constructs a presenter for the given factory.
   *
   * @param atypeFactory the AnnotatedTypeFactory for the current analysis
   */
  public ConformanceTypeInformationPresenter(AnnotatedTypeFactory atypeFactory) {
    super(atypeFactory);
  }

  @Override
  protected AnnotatedTypeFormatter createTypeFormatter() {
    // Use the same type formatter as normal error messages. Look into whether a different format
    // would be better here.
    return atypeFactory.getAnnotatedTypeFormatter();
  }

  @Override
  protected TypeInformationReporter createTypeInformationReporter(ClassTree tree) {
    return new ConformanceTypeInformationReporter(tree);
  }

  class ConformanceTypeInformationReporter extends TypeInformationReporter {
    ConformanceTypeInformationReporter(ClassTree tree) {
      super(tree);
    }

    @Override
    protected void reportTreeType(
        Tree tree, AnnotatedTypeMirror type, TypeOccurrenceKind occurrenceKind) {
      switch (tree.getKind()) {
        case ASSIGNMENT:
          AssignmentTree asgn = (AssignmentTree) tree;
          AnnotatedTypeMirror varType =
              genFactory != null
                  ? genFactory.getAnnotatedTypeLhs(asgn.getVariable())
                  : atypeFactory.getAnnotatedType(asgn.getVariable());
          checker.reportWarning(
              asgn.getVariable(),
              "sinkType",
              typeFormatter.format(varType),
              asgn.getVariable().toString());
          break;
        case RETURN:
          checker.reportWarning(tree, "sinkType", typeFormatter.format(type), "return");
          break;
        case METHOD_INVOCATION:
          ExecutableElement calledElem = TreeUtils.elementFromUse((MethodInvocationTree) tree);
          String methodName = calledElem.getSimpleName().toString();
          AnnotatedExecutableType calledType = (AnnotatedExecutableType) type;
          List<? extends AnnotatedTypeMirror> params = calledType.getParameterTypes();

          for (int i = 0; i < params.size(); ++i) {
            String paramName = calledElem.getParameters().get(i).getSimpleName().toString();
            String paramLocation = String.format("%s#%s", methodName, paramName);
            checker.reportWarning(
                tree, "sinkType", typeFormatter.format(params.get(i)), paramLocation);
          }
          break;
        default:
          // Nothing special for other trees.
      }

      if (TreeUtils.isExpressionTree(tree)) {
        checker.reportWarning(tree, "sourceType", typeFormatter.format(type), tree.toString());
      }
    }
  }
}
