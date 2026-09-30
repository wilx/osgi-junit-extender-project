package fixtures.tests;
public final class CountingListener extends org.junit.runner.notification.RunListener {
  private int count;
  @Override
  public void testStarted(org.junit.runner.Description description) {
    count++;
  }
  public int getCount() {
    return count;
  }
}
