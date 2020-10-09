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

import static java.lang.annotation.ElementType.TYPE_USE;
import static org.checkerframework.framework.qual.LiteralKind.PRIMITIVE;
import static org.checkerframework.framework.qual.LiteralKind.STRING;
import static org.checkerframework.framework.qual.TypeUseLocation.CONSTRUCTOR_RESULT;
import static org.checkerframework.framework.qual.TypeUseLocation.RECEIVER;

import java.lang.annotation.Target;
import org.checkerframework.framework.qual.DefaultFor;
import org.checkerframework.framework.qual.InvisibleQualifier;
import org.checkerframework.framework.qual.QualifierForLiterals;
import org.checkerframework.framework.qual.SubtypeOf;

@Target(TYPE_USE)
@SubtypeOf(NullnessUnspecified.class)
@DefaultFor({CONSTRUCTOR_RESULT, RECEIVER})
@QualifierForLiterals({PRIMITIVE, STRING})
@InvisibleQualifier
@interface NoAdditionalNullness {}
