import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
class SimpleSample {
  Object passThrough(@Nullable Object mightBeNull) {
    return mightBeNull;
  }
}
