// Copyright 2023 The JSpecify Authors
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

package org.jspecify.conformance;

import java.nio.file.Path;

/** An expected or reported fact within a test input file. */
abstract class Fact {

  private final Path file;
  private final long lineNumber;

  protected Fact(Path file, long lineNumber) {
    this.file = file;
    this.lineNumber = lineNumber;
  }

  /** The file path relative to the test source root. */
  final Path getFile() {
    return file;
  }

  /** Returns the line number of the code in the source file to which this fact applies. */
  final long getLineNumber() {
    return lineNumber;
  }

  /** The text form of the fact. */
  protected abstract String getFactText();

  /** Returns an object that helps to identify this fact within a file. */
  public String getIdentifier() {
    return String.valueOf(getLineNumber());
  }
}
