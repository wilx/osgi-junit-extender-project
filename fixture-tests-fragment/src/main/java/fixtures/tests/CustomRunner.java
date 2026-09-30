package fixtures.tests;
public final class CustomRunner extends org.junit.runners.BlockJUnit4ClassRunner {
  public CustomRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
    super(type);
  }
}
