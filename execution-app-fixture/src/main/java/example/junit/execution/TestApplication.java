package example.junit.execution;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.osgi.framework.*;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.*;

/** Minimal execution fixture: the extender owns visibility, this application owns execution. */
public final class TestApplication implements IApplication {
  @Override
  public Object start(IApplicationContext applicationContext) {
    Map<?, ?> arguments = applicationContext.getArguments();
    Map<String, Object> report = new LinkedHashMap<>();
    Thread thread = Thread.currentThread();
    ClassLoader original = thread.getContextClassLoader();
    try {
      BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
      Bundle target = context.getBundle(((Number) arguments.get("bundleId")).longValue());
      if (target == null)
        throw new IllegalStateException("Testing bundle is absent");
      BundleWiring wiring = effectiveWiring(target, (Number) arguments.get("hostId"));
      ClassLoader loader = wiring.getClassLoader();
      if (loader == null)
        throw new IllegalStateException("Testing class loader is absent");
      thread.setContextClassLoader(loader);
      requireReadyHook(context, wiring);
      if (Boolean.TRUE.equals(arguments.get("runnerFirst"))) {
        loader.loadClass("org.junit.runner.JUnitCore");
      }
      String seed = (String) arguments.get("seed");
      if (seed != null)
        Class.forName(seed, false, loader);
      long dynamicCount = wiring.getRequirements(PackageNamespace.PACKAGE_NAMESPACE)
                              .stream()
                              .filter(q -> "dynamic".equals(q.getDirectives().get("resolution")))
                              .count();
      if (dynamicCount == 0)
        throw new IllegalStateException("Bootstrap did not establish dynamic imports");
      String[] names = (String[]) arguments.get("tests");
      if (names == null || names.length == 0)
        throw new IllegalArgumentException("No tests selected");
      Class<?>[] tests = new Class<?>[names.length];
      for (int i = 0; i < names.length; i++) {
        tests[i] = Class.forName(names[i], false, loader);
        if (tests[i].getClassLoader() != loader)
          throw new IllegalStateException("Test escaped target loader");
      }
      Class<?> coreType = Class.forName("org.junit.runner.JUnitCore", true, loader);
      Object core = coreType.getConstructor().newInstance();
      Object listener = null;
      if (arguments.containsKey("listener")) {
        Class<?> listenerType = loader.loadClass((String) arguments.get("listener"));
        listener = listenerType.getConstructor().newInstance();
        coreType
            .getMethod("addListener", loader.loadClass("org.junit.runner.notification.RunListener"))
            .invoke(core, listener);
      }
      Object result = coreType.getMethod("run", Class[].class).invoke(core, (Object) tests);
      Class<?> resultType = loader.loadClass("org.junit.runner.Result");
      int count = (Integer) resultType.getMethod("getRunCount").invoke(result);
      int failures = (Integer) resultType.getMethod("getFailureCount").invoke(result);
      if (count == 0)
        throw new IllegalStateException("JUnit executed zero tests");
      report.put("tests", count);
      report.put("failures", failures);
      report.put("status", failures == 0 ? "passed" : "failed");
      List<String> messages = new ArrayList<>();
      for (Object failure : (List<?>) resultType.getMethod("getFailures").invoke(result)) {
        messages.add(failure.toString());
      }
      report.put("messages", messages);
      if (listener != null)
        report.put("listenerCount", listener.getClass().getMethod("getCount").invoke(listener));
      report.put("hostId", wiring.getBundle().getBundleId());
      report.put("dynamicRequirements", dynamicCount);
      report.put("junitBundle", FrameworkUtil.getBundle(coreType).getSymbolicName());
    } catch (Exception | LinkageError failure) {
      Throwable cause = failure instanceof InvocationTargetException && failure.getCause() != null
          ? failure.getCause()
          : failure;
      report.put("status", "error");
      report.put("errorType", cause.getClass().getName());
      report.put("error", String.valueOf(cause.getMessage()));
    } finally {
      thread.setContextClassLoader(original);
      report.put("tcclRestored", thread.getContextClassLoader() == original);
    }
    return report;
  }

  private static BundleWiring effectiveWiring(Bundle target, Number selectedHost) {
    BundleRevision current = target.adapt(BundleRevision.class);
    if (current == null)
      throw new IllegalStateException("Testing bundle is absent");
    if ((current.getTypes() & BundleRevision.TYPE_FRAGMENT) == 0) {
      BundleWiring wiring = current.getWiring();
      if (wiring == null)
        throw new IllegalStateException("Testing bundle is unresolved");
      return wiring;
    }
    // An updated fragment's current revision may be unresolved while the old
    // revision is still attached. Select only real, in-use attachment wires.
    Set<BundleWiring> hosts = new LinkedHashSet<>();
    for (BundleRevision revision : target.adapt(BundleRevisions.class).getRevisions()) {
      BundleWiring wiring = revision.getWiring();
      if (wiring == null || !wiring.isInUse())
        continue;
      for (BundleWire wire : wiring.getRequiredWires(HostNamespace.HOST_NAMESPACE)) {
        BundleWiring provider = wire.getProviderWiring();
        if (selectedHost == null || provider.getBundle().getBundleId() == selectedHost.longValue())
          hosts.add(provider);
      }
    }
    if (hosts.isEmpty())
      throw new IllegalStateException("Fragment has no actual selected host wire");
    if (hosts.size() != 1)
      throw new IllegalArgumentException(
          "Select a fragment host explicitly; refresh ambiguous revisions");
    return hosts.iterator().next();
  }

  private static void requireReadyHook(BundleContext context, BundleWiring wiring)
      throws InvalidSyntaxException {
    ServiceReference<?>[] hooks = context.getServiceReferences(
        "org.osgi.framework.hooks.weaving.WeavingHook", "(example.junit.extender=true)");
    if (hooks == null || hooks.length == 0)
      throw new IllegalStateException("JUnit weaving hook is not registered");
    List<BundleRevision> origins = new ArrayList<>();
    origins.add(wiring.getRevision());
    List<BundleWire> attachments = wiring.getProvidedWires(HostNamespace.HOST_NAMESPACE);
    if (attachments != null)
      for (BundleWire wire : attachments) origins.add(wire.getRequirement().getRevision());
    for (ServiceReference<?> reference : hooks) {
      Bundle provider = reference.getBundle();
      if (provider == null || provider.getState() != Bundle.ACTIVE)
        continue;
      List<BundleCapability> capabilities =
          provider.adapt(BundleRevision.class).getDeclaredCapabilities("junit");
      for (BundleRevision origin : origins)
        for (BundleRequirement requirement : origin.getDeclaredRequirements("junit")) {
          if (!"active".equals(requirement.getDirectives().get("effective")))
            continue;
          for (BundleCapability capability : capabilities)
            if (requirement.matches(capability))
              return;
        }
    }
    throw new IllegalStateException("No active junit declaration matches a ready extender");
  }
  @Override
  public void stop() {}
}
