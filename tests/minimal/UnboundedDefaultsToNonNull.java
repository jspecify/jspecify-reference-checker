// Test case for Issue 161:
// https://github.com/jspecify/jspecify-reference-checker/issues/161

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
class UnboundedDefaultsToNonNull<E extends @Nullable Object> {
  UnboundedDefaultsToNonNull<?> x() {
    return this;
  }
}
