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

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import java.util.List;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeFormatter;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.util.visualize.AbstractTypeInformationPresenter;
import org.checkerframework.framework.util.visualize.TypeOccurrenceKind;

public class ConformanceTypeInformationPresenter extends AbstractTypeInformationPresenter {

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

      String direction = null;
      String usage = null;

      switch (tree.getKind()) {
        case RETURN:
          direction = "sinkType";
          usage = "return";
          break;

        case METHOD_INVOCATION:
          AnnotatedExecutableType calledType = (AnnotatedExecutableType) type;
          List<? extends AnnotatedTypeMirror> params = calledType.getParameterTypes();
          MethodInvocationTree mit = (MethodInvocationTree) tree;
          List<? extends ExpressionTree> args = mit.getArguments();
          assert params.size() == args.size();

          for (int i = 0; i < params.size(); ++i) {
            checker.reportError(
                tree,
                "sinkType",
                typeFormatter.format(params.get(i)),
                // TODO: build up something from method and parameter name?
                "param");

            checker.reportError(
                tree,
                "sourceType",
                typeFormatter.format(atypeFactory.getAnnotatedType(args.get(i))),
                // TODO: build up something from method and parameter name?
                "arg");
          }
          break;
        case IDENTIFIER:
          if (occurrenceKind == TypeOccurrenceKind.USE_TYPE) {
            direction = "sourceType";
            usage = tree.toString();
          }
          break;
        default:
      }

      if (direction != null && usage != null) {
        checker.reportError(tree, direction, typeFormatter.format(type), usage);
      }
    }
  }
}
