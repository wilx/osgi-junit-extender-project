package fixtures.ordinary;
public class OrdinaryTest {
  @org.junit.Test
  public void assertion() {
    org.junit.Assert.assertEquals(4, 2 + 2);
  }
  @org.junit.Test
  public void tccl() {
    org.junit.Assert.assertNotNull(org.osgi.framework.FrameworkUtil.getBundle(getClass()));
    org.junit.Assert.assertSame(
        getClass().getClassLoader(), Thread.currentThread().getContextClassLoader());
  }
}
