package example.junit.extender;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ImportPolicyTest {
  @Test
  void defaultsCoverReferencePackages() {
    assertEquals(35, ImportPolicy.parse(null).size());
  }
  @Test
  void rootAndSubpackagesAreDistinctAndQuotedCommasArePreserved() {
    assertEquals(2,
        ImportPolicy.parse("org.junit;version=\"[4.13.2,5)\", org.junit.*;version=\"[4.13.2,5)\"")
            .size());
  }
  @Test
  void rejectUnboundedBroadInvalidOrProviderSpecificPolicies() {
    for (String value : new String[] {"", "*;version=\"[1,2)\"", "org.junit", "org.junit;version=4",
             "org.junit;version=\"[5,4)\"",
             "org.junit;version=\"[4,5)\";bundle-symbolic-name=vendor", "org.junit;version=\"[4,5)",
             "org.junit;version=\"[4,5)\",org.junit;version=\"[4,5)\"",
             "org.junit.*;version=\"[4,5)\",org.junit.runner;version=\"[4,5)\"",
             "java.lang;version=\"[1,2)\""}) {
      assertThrows(IllegalArgumentException.class, () -> ImportPolicy.parse(value), value);
    }
  }
  @Test
  void normalizeGeneratedFilterGroupingAndOrder() {
    assertEquals(
        FilterKey.of("(&(osgi.wiring.package=org.junit)(&(version>=4.13.2)(!(version>=5.0.0))))"),
        FilterKey.of("(&(!(version>=5.0.0))(osgi.wiring.package=org.junit)(version>=4.13.2))"));
    assertNotEquals(FilterKey.of("(osgi.wiring.package=org.junit)"),
        FilterKey.of("(osgi.wiring.package=org.junit.*)"));
  }
}
