package example.junit.extender;

import java.util.List;
import org.osgi.framework.Bundle;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

/** Stateless with respect to consumer lifecycles, including live fragment attachment. */
final class JUnitWeavingHook implements WeavingHook {
  private final Bundle implementation;
  private final List<BundleCapability> capabilities;
  private final ImportPolicy policy;

  JUnitWeavingHook(
      Bundle implementation, List<BundleCapability> capabilities, ImportPolicy policy) {
    this.implementation = implementation;
    this.capabilities = List.copyOf(capabilities);
    this.policy = policy;
  }

  @Override
  public void weave(WovenClass wovenClass) {
    BundleWiring wiring = wovenClass.getBundleWiring();
    if (wiring == null || wiring.getBundle() == implementation)
      return;
    if (!eligible(wiring))
      return;
    // Read committed requirements on every callback. Attachment can mutate the same
    // wiring object, and additions are not committed until after weave() returns.
    // No locks, consumer class loading, logging, or premature processed flag here.
    policy.addMissing(wiring, wovenClass.getDynamicImports());
  }

  private boolean eligible(BundleWiring wiring) {
    if (qualifies(wiring.getRevision()))
      return true;
    List<BundleWire> attachments = wiring.getProvidedWires(HostNamespace.HOST_NAMESPACE);
    if (attachments != null)
      for (BundleWire attachment : attachments) {
        // Use the attached revision, even when a newer revision is now installed.
        if (qualifies(attachment.getRequirement().getRevision()))
          return true;
      }
    return false;
  }

  private boolean qualifies(BundleRevision revision) {
    for (BundleRequirement requirement : revision.getDeclaredRequirements("junit")) {
      // active is an explicit application contract; the framework does not enforce it.
      if (!"active".equals(requirement.getDirectives().get("effective")))
        continue;
      for (BundleCapability capability : capabilities)
        if (requirement.matches(capability))
          return true;
    }
    return false;
  }
}
