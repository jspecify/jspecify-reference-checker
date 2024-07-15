package demo;

import java.lang.reflect.Method;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
class Demo {
  void conflict(@Nullable int i) {}

  Object incompatible(@Nullable Object in) {
    return in;
  }

  String deref(@Nullable Object in) {
    return in.toString();
  }

  void jdkDemo(Method m) throws Exception {
    // Demo to ensure the jspecify/jdk is used. No error expected. eisop/jdk would give an error.
    m.invoke(null);
  }
}
