package example.junit.extender;

import java.util.Hashtable;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.wiring.BundleRevision;

/** Registers only the package-visibility hook; it never starts tests or refreshes consumers. */
public final class Activator implements BundleActivator {
  private ServiceRegistration<WeavingHook> registration;

  @Override
  public void start(BundleContext context) {
    ImportPolicy policy = ImportPolicy.parse(context.getProperty("example.junit.extender.imports"));
    if ("false".equalsIgnoreCase(context.getProperty("example.junit.extender.enabled")))
      return;
    JUnitWeavingHook hook = new JUnitWeavingHook(context.getBundle(),
        context.getBundle().adapt(BundleRevision.class).getDeclaredCapabilities("junit"), policy);
    // Prepare parser and helper classes before the service can receive reentrant callbacks.
    policy.prepare();
    Hashtable<String, Object> properties = new Hashtable<>();
    properties.put("example.junit.extender", Boolean.TRUE);
    registration = context.registerService(WeavingHook.class, hook, properties);
  }

  @Override
  public void stop(BundleContext context) {
    if (registration != null) {
      registration.unregister();
      registration = null;
    }
  }
}
