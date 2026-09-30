package fixtures.production;
public final class Calculator {
  static int packagePrivateAdd(int a, int b) {
    return a + b;
  }
  public static String normalOperation() {
    return "production";
  }
}
