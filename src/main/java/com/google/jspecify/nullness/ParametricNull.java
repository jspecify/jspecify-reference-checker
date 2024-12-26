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

import static java.lang.annotation.ElementType.TYPE_USE;

import java.lang.annotation.Target;
import org.checkerframework.framework.qual.InvisibleQualifier;
import org.checkerframework.framework.qual.ParametricTypeVariableUseQualifier;

/** Internal implementation detail; not usable in user code. */
@Target(TYPE_USE)
@InvisibleQualifier
@ParametricTypeVariableUseQualifier(Nullable.class)
@interface ParametricNull {}
