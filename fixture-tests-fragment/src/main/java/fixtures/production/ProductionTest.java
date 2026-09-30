package fixtures.production;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

import org.junit.Test;
public class ProductionTest {
  @Test
  public void packagePrivateAccess() {
    assertEquals(7, Calculator.packagePrivateAdd(3, 4));
    assertSame(getClass().getClassLoader(), Calculator.class.getClassLoader());
  }
  @Test
  public void hamcrest() {
    assertThat("fragment", startsWith("frag"));
  }
  @Test
  public void tccl() {
    assertSame(getClass().getClassLoader(), Thread.currentThread().getContextClassLoader());
  }
}
