package fixtures.tests;
public class IntentionalFailure {
  @org.junit.Test
  public void expectedFailure() {
    org.junit.Assert.fail("intentional failure sentinel");
  }
}
