package example.junit.it;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.*;
import org.osgi.framework.*;
import org.osgi.framework.hooks.weaving.*;
import org.osgi.framework.launch.*;
import org.osgi.framework.namespace.*;
import org.osgi.framework.wiring.*;

/** Runs with only this main JAR and the framework JAR on the process class path. */
public final class ScenarioMain {
  private final Path bundles, directory;
  private Framework framework;
  private BundleContext context;
  private Bundle app, extender, provider, host, fragment;
  private final List<FrameworkEvent> errors = new CopyOnWriteArrayList<>();
  private final BlockingQueue<FrameworkEvent> errorEvents = new LinkedBlockingQueue<>();
  private static final String[] TESTS = {
      "fixtures.production.ProductionTest", "fixtures.tests.RunnerTest"};
  private static final String SEED = "fixtures.seed.FragmentSeed";
  private ScenarioMain(Path bundles, Path directory) {
    this.bundles = bundles;
    this.directory = directory;
  }

  public static void main(String[] args) throws Exception {
    ScenarioMain run = new ScenarioMain(Path.of(args[1]), Path.of(args[2]));
    Throwable failure = null;
    try {
      run.start(args[0]);
      run.scenario(args[0]);
      check(run.errors.isEmpty(),
          "Unexpected framework errors: "
              + run.errors.stream().map(e -> e.getThrowable().toString()).toList());
      System.out.println("SCENARIO PASSED " + args[0]);
    } catch (Throwable t) {
      failure = t;
      t.printStackTrace();
    } finally {
      if (run.framework != null) {
        run.framework.stop();
        FrameworkEvent stopped = run.framework.waitForStop(30000);
        if (stopped.getType() == FrameworkEvent.WAIT_TIMEDOUT)
          throw new AssertionError("Framework shutdown timed out");
      }
    }
    if (failure != null)
      throw new AssertionError("Scenario failed", failure);
  }
  private void start(String scenario) throws Exception {
    for (String type :
        List.of("org.junit.Test", "org.junit.runner.JUnitCore", "org.hamcrest.Matcher")) {
      expectMissing(() -> Class.forName(type, false, getClass().getClassLoader()));
    }
    Map<String, String> config = new HashMap<>();
    config.put(Constants.FRAMEWORK_STORAGE, directory.resolve("storage").toString());
    config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
    config.put(Constants.FRAMEWORK_BUNDLE_PARENT, Constants.FRAMEWORK_BUNDLE_PARENT_BOOT);
    config.put("osgi.compatibility.bootdelegation", "false");
    config.put("eclipse.ignoreApp", "true");
    if (scenario.equals("no-hook"))
      config.put("example.junit.extender.enabled", "false");
    if (scenario.equals("wildcards"))
      config.put("example.junit.extender.imports",
          "org.junit;version=\"[4.13.2,5)\",org.junit.*;version=\"[4.13.2,5)\",junit.*;version=\"["
          + "4.13.2,5)\",org.hamcrest;version=\"[1.3,2)\",org.hamcrest.*;version=\"[1.3,2)\"");
    if (scenario.equals("version-range"))
      config.put("example.junit.extender.imports", "org.junit;version=\"[4.14,5)\"");
    if (scenario.equals("configuration"))
      config.put("example.junit.extender.imports", "org.junit;version=\"[4.13.2,5)\"");
    framework = ServiceLoader.load(FrameworkFactory.class).iterator().next().newFramework(config);
    framework.init();
    context = framework.getBundleContext();
    context.addFrameworkListener(e -> {
      if (e.getType() == FrameworkEvent.ERROR) {
        errors.add(e);
        errorEvents.add(e);
      }
    });
    framework.start();
    context.installBundle(bundles.resolve("common.jar").toUri().toString()).start();
    context.installBundle(bundles.resolve("registry.jar").toUri().toString()).start();
    app = context.installBundle(bundles.resolve("app.jar").toUri().toString());
    app.start();
    install("execution-app-fixture").start();
    if (!scenario.equals("missing-provider")) {
      if (scenario.equals("alternative")) {
        install("alternative-hamcrest-provider").start();
        provider = install("alternative-junit-provider");
      } else
        provider = installFile(bundles.resolve("servicemix.jar"));
      check(resolve(provider), "Provider failed resolution");
    }
    if (!scenario.equals("absent-extender")) {
      extender = install("junit-import-extender");
      extender.start();
    }
    inspectArtifacts();
  }
  private Bundle install(String artifact) throws Exception {
    return installFile(bundles.resolve(artifact + ".jar"));
  }
  private Bundle installFile(Path path) throws Exception {
    return context.installBundle(path.toUri().toString());
  }
  private boolean resolve(Bundle... targets) {
    return framework.adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(targets));
  }
  private static BundleWiring wiring(Bundle bundle) {
    return bundle.adapt(BundleWiring.class);
  }
  private void fixtures(boolean late) throws Exception {
    host = install("fixture-production");
    if (late) {
      host.start();
      check("production".equals(host.loadClass("fixtures.production.Calculator")
                    .getMethod("normalOperation")
                    .invoke(null)),
          "Host pre-load");
      check(dynamic(host) == 0, "Ineligible host received imports");
    }
    BundleWiring before = wiring(host);
    ClassLoader loader = before == null ? null : before.getClassLoader();
    fragment = install("fixture-tests-fragment");
    check(resolve(fragment, host), "Fragment failed resolution");
    attached(fragment, host);
    check(wiring(fragment).getClassLoader() == null, "Fragment has independent loader");
    check(wiring(host).getRequiredWires("junit").isEmpty(),
        "active requirement created capability wire");
    check(wiring(host).getRequirements("junit").isEmpty(),
        "active requirement visible in runtime requirements");
    check(wiring(fragment).getRevision().getDeclaredRequirements("junit").size() == 1,
        "Declaration missing");
    if (late) {
      check(wiring(host) == before, "Late attachment changed host wiring");
      check(wiring(host).getClassLoader() == loader, "Late attachment changed loader");
      check(host.getState() == Bundle.ACTIVE, "Late attachment stopped host");
      System.out.println("Late attachment retained host wiring and class loader.");
    }
  }
  private void scenario(String name) throws Exception {
    switch (name) {
      case "early", "late", "alternative" -> {
        fixtures(name.equals("late"));
        success(fragment, host, SEED, TESTS, 4);
        visibility();
      }
      case "refresh" -> {
        host = install("fixture-production");
        host.start();
        Class<?> old = host.loadClass("fixtures.production.Calculator");
        fragment = install("fixture-tests-fragment");
        refresh(host);
        check(resolve(host, fragment), "Resolve after refresh");
        attached(fragment, host);
        check(host.loadClass(old.getName()) != old, "Refresh reused class identity");
        success(fragment, host, SEED, TESTS, 4);
        visibility();
      }
      case "ordinary" -> {
        Bundle ordinary = install("fixture-ordinary-tests");
        check(resolve(ordinary), "Ordinary resolve");
        success(ordinary, null, "fixtures.ordinary.Seed",
            new String[] {"fixtures.ordinary.OrdinaryTest"}, 2);
        check(dynamic(ordinary) == 35, "Ordinary imports");
      }
      case "no-hook", "absent-extender" -> {
        fixtures(true);
        Map<String, Object> result = execute(fragment, host, SEED, TESTS, false);
        error(result, "hook is not registered");
        host.loadClass(SEED);
        check(dynamic(host) == 0, "Absent hook injected imports");
        expectMissing(() -> host.loadClass("org.junit.runner.JUnitCore"));
      }
      case "missing-provider" -> {
        fixtures(true);
        error(execute(fragment, host, SEED, TESTS, false), "org.junit");
        check(dynamic(host) == 35, "Missing provider hid injection");
      }
      case "nonqualifying" -> {
        host = install("fixture-production");
        host.start();
        fragment = installFile(variant(
            "fixture-tests-fragment", "no-requirement", Map.of(), Set.of("Require-Capability")));
        check(resolve(fragment), "Nonqualifying fragment resolve");
        attached(fragment, host);
        host.loadClass(SEED);
        check(dynamic(host) == 0, "Unmarked fragment injected");
        expectMissing(() -> host.loadClass("org.junit.Test"));
        Bundle ordinary = installFile(variant(
            "fixture-ordinary-tests", "unmarked-ordinary", Map.of(), Set.of("Require-Capability")));
        check(resolve(ordinary), "Unmarked ordinary resolve");
        ordinary.loadClass("fixtures.ordinary.Seed");
        check(dynamic(ordinary) == 0, "Unmarked ordinary injected");
      }
      case "resolve-control" -> {
        host = install("fixture-production");
        host.start();
        fragment = installFile(variant("fixture-tests-fragment", "resolve-effective",
            Map.of("Require-Capability", "junit"), Set.of()));
        check(!resolve(fragment), "Resolve-effective fragment attached late unexpectedly");
        check(wiring(fragment) == null, "Rejected fragment wired");
        refresh(host);
        check(resolve(host, fragment), "Resolve-effective refresh failed");
        attached(fragment, host);
        check(!wiring(host).getRequiredWires("junit").isEmpty(), "Missing resolve-effective wire");
        host.loadClass(SEED);
        check(dynamic(host) == 0, "Resolve-effective control incorrectly opted in");
        // Removing the only capability provider affects resolution for this control.
        extender.uninstall();
        refresh(extender, host, fragment);
        Bundle ordinary = installFile(variant("fixture-ordinary-tests", "mandatory",
            Map.of("Require-Capability", "junit"), Set.of()));
        check(!resolve(ordinary), "Mandatory resolve requirement ignored");
      }
      case "filters" -> {
        host = install("fixture-production");
        host.start();
        fragment = installFile(variant("fixture-tests-fragment", "unmatched-filter",
            Map.of("Require-Capability", "junit;effective:=active;filter:=\"(flavour=absent)\""),
            Set.of()));
        check(resolve(fragment), "active filter incorrectly blocked resolution");
        attached(fragment, host);
        error(execute(fragment, host, SEED, TESTS, false), "No active junit declaration");
        host.loadClass(SEED);
        check(dynamic(host) == 0, "Unmatched filter injected");
        Bundle match = installFile(variant("fixture-ordinary-tests", "matching-filter",
            Map.of("Require-Capability", "junit;effective:=active;filter:=\"(!(flavour=absent))\""),
            Set.of()));
        check(resolve(match), "Matching filter resolve");
        success(match, null, "fixtures.ordinary.Seed",
            new String[] {"fixtures.ordinary.OrdinaryTest"}, 2);
        Bundle optional = installFile(variant("fixture-ordinary-tests", "optional-active",
            Map.of("Require-Capability", "junit;effective:=active;resolution:=optional",
                "Bundle-SymbolicName", "example.fixture.optional"),
            Set.of()));
        check(resolve(optional), "Optional active resolve");
        success(optional, null, "fixtures.ordinary.Seed",
            new String[] {"fixtures.ordinary.OrdinaryTest"}, 2);
      }
      case "ordering" -> {
        fixtures(true);
        error(execute(fragment, host, SEED, TESTS, true), "org.junit.runner.JUnitCore");
        check(dynamic(host) == 0, "Runner-first injected without local definition");
        success(fragment, host, SEED, TESTS, 4);
        visibility();
      }
      case "restart" -> {
        extender.stop();
        fixtures(true);
        host.loadClass(SEED);
        check(dynamic(host) == 0, "Stopped hook injected");
        extender.start();
        error(execute(fragment, host, SEED, TESTS, false), "Bootstrap did not establish");
        success(fragment, host, "fixtures.seed.FragmentSeedAfter", TESTS, 4);
        long count = dynamic(host);
        extender.stop();
        check(dynamic(host) == count, "Stopping removed runtime imports");
        extender.start();
        success(fragment, host, SEED, TESTS, 4);
        check(dynamic(host) == count, "Restart duplicated imports");
        refresh(host);
        success(fragment, host, SEED, TESTS, 4);
        check(dynamic(host) == 35, "Refreshed wiring not processed");
      }
      case "update" -> {
        fixtures(true);
        BundleRevision oldFragment = wiring(fragment).getRevision();
        ClassLoader oldLoader = wiring(host).getClassLoader();
        Path unmarked = variant(
            "fixture-tests-fragment", "update-unmarked", Map.of(), Set.of("Require-Capability"));
        try (InputStream in = Files.newInputStream(unmarked)) {
          fragment.update(in);
        }
        check(wiring(host)
                    .getProvidedWires(HostNamespace.HOST_NAMESPACE)
                    .get(0)
                    .getRequirement()
                    .getRevision()
                == oldFragment,
            "Update replaced old attached revision without refresh");
        // First injection must consult the old attached declaration, not the new unmarked revision.
        success(fragment, host, SEED, TESTS, 4);
        refresh(host);
        check(resolve(host, fragment), "Updated fragment resolve");
        error(execute(fragment, host, SEED, TESTS, false), "No active junit declaration");
        host.loadClass(SEED);
        check(dynamic(host) == 0, "Stale eligibility survived fragment refresh");
        check(wiring(host).getClassLoader() != oldLoader, "Fragment refresh reused host loader");
        try (InputStream in = Files.newInputStream(bundles.resolve("fixture-tests-fragment.jar"))) {
          fragment.update(in);
        }
        refresh(host);
        check(resolve(host, fragment), "Restored fragment resolve");
        success(fragment, host, SEED, TESTS, 4);
        ClassLoader next = wiring(host).getClassLoader();
        try (InputStream in = Files.newInputStream(bundles.resolve("fixture-production.jar"))) {
          host.update(in);
        }
        refresh(host);
        check(resolve(host, fragment), "Host update resolve");
        success(fragment, host, SEED, TESTS, 4);
        check(wiring(host).getClassLoader() != next, "Host update reused loader");
      }

      case "uninstall" -> {
        fixtures(true);
        success(fragment, host, SEED, TESTS, 4);
        fragment.uninstall();
        check(!wiring(host).getProvidedWires(HostNamespace.HOST_NAMESPACE).isEmpty(),
            "Uninstall unexpectedly detached immediately");
        refresh(host);
        check(resolve(host), "Host after uninstall");
        host.loadClass("fixtures.production.Calculator");
        check(dynamic(host) == 0, "Refreshed unqualified host injected");
        check(wiring(host).getProvidedWires(HostNamespace.HOST_NAMESPACE).isEmpty(),
            "Stale attachment");
      }
      case "concurrent" -> {
        fixtures(true);
        host.loadClass(SEED);
        check(dynamic(host) == 35, "Bootstrap count");
        for (int wave = 0; wave < 4; wave++) loadConcurrent(wave * 16, 16, false);
        check(dynamic(host) == 35, "Concurrent definitions grew imports");
        success(fragment, host, SEED, TESTS, 4);
      }
      case "recursive" -> {
        fixtures(true);
        Bundle nestedTarget = install("fixture-ordinary-tests");
        check(resolve(nestedTarget), "Nested ordinary resolve");
        AtomicInteger depth = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        WeavingHook nested = wc -> {
          if (wc.getBundleWiring() != wiring(host) && wc.getBundleWiring() != wiring(nestedTarget))
            return;
          int d = depth.incrementAndGet();
          max.accumulateAndGet(d, Math::max);
          try {
            if (wc.getClassName().equals("fixtures.probes.Probe0"))
              nestedTarget.loadClass("fixtures.ordinary.Seed");
          } catch (ClassNotFoundException e) {
            throw new WeavingException("Nested probe failed", e);
          } finally {
            depth.decrementAndGet();
          }
        };
        ServiceRegistration<WeavingHook> reg =
            context.registerService(WeavingHook.class, nested, null);
        try {
          host.loadClass("fixtures.probes.Probe0");
        } finally {
          reg.unregister();
        }
        check(max.get() >= 2, "No actual nested weaving callbacks");
        check(dynamic(host) == 35 && dynamic(nestedTarget) == 35,
            "Nested eligible wiring lost imports or duplicated them");
        success(nestedTarget, null, "fixtures.ordinary.Seed",
            new String[] {"fixtures.ordinary.OrdinaryTest"}, 2);
        success(fragment, host, SEED, TESTS, 4);
      }

      case "first-race" -> {
        fixtures(true);
        loadConcurrent(0, 16, true);
        long count = dynamic(host);
        check(count == 35 * 16, "Barrier did not expose the expected precommit race: " + count);
        check(wiring(host)
                    .getRequirements(PackageNamespace.PACKAGE_NAMESPACE)
                    .stream()
                    .filter(q -> "dynamic".equals(q.getDirectives().get("resolution")))
                    .map(q -> q.getDirectives().get("filter"))
                    .distinct()
                    .count()
                == 35,
            "Lost distinct package requirements");
        loadConcurrent(16, 16, false);
        check(dynamic(host) == count, "Committed requirements keep growing");
        System.out.println("Unprepared first-definition race: " + count
            + (" raw requirements; 35 distinct clauses. Controlled bootstrap is required for exact "
                + "deduplication."));
        success(fragment, host, SEED, TESTS, 4);
      }
      case "multiple-providers" -> {
        Bundle hamcrest = install("alternative-hamcrest-provider");
        Bundle junit = install("alternative-junit-provider");
        check(resolve(hamcrest, junit), "Alternative providers resolve");
        fixtures(true);
        success(fragment, host, SEED, TESTS, 4);
        visibility();
      }
      case "multiple-hosts" -> {
        host = install("fixture-production");
        Bundle second = installFile(
            variant("fixture-production", "host-v2", Map.of("Bundle-Version", "2.0.0"), Set.of()));
        fragment = install("fixture-tests-fragment");
        check(resolve(host, second, fragment), "Multiple-host resolve");
        check(wiring(fragment).getRequiredWires(HostNamespace.HOST_NAMESPACE).size() == 2,
            "Expected two attached hosts");
        error(execute(fragment, null, SEED, TESTS, false), "Select a fragment host");
        success(fragment, host, SEED, TESTS, 4);
        success(fragment, second, SEED, TESTS, 4);
        check(host.loadClass(TESTS[0]) != second.loadClass(TESTS[0]),
            "Hosts shared test Class object");
      }
      case "failures" -> {
        fixtures(true);
        Map<String, Object> failure = execute(
            fragment, host, SEED, new String[] {"fixtures.tests.IntentionalFailure"}, false);
        check("failed".equals(failure.get("status")), "Intentional failure lost: " + failure);
        check(
            failure.get("tests").equals(1) && failure.get("failures").equals(1), "Failure counts");
        check(failure.get("messages").toString().contains("intentional failure sentinel"),
            "Failure message lost");
        error(execute(fragment, host, SEED, new String[] {"fixtures.tests.DoesNotExist"}, false),
            "DoesNotExist");
        success(fragment, host, SEED, new String[] {"fixtures.tests.LegacyTest"}, 1);
        success(fragment, host, SEED, TESTS, 4);
      }
      case "configuration" -> {
        fixtures(true);
        host.loadClass(SEED);
        check(dynamic(host) == 1, "Configured import count ignored");
        check(host.loadClass("org.junit.Test") != null, "Configured root package unavailable");
        expectMissing(() -> host.loadClass("org.junit.runner.JUnitCore"));
      }
      case "unattached" -> {
        host = install("fixture-production");
        host.start();
        fragment = installFile(variant("fixture-tests-fragment", "wrong-host",
            Map.of("Fragment-Host", "absent.host"), Set.of()));
        check(!resolve(fragment), "Unmatched fragment resolved");
        host.loadClass("fixtures.production.Calculator");
        check(dynamic(host) == 0, "Unattached fragment qualified host");
        error(execute(fragment, host, SEED, TESTS, false), "no actual selected host wire");
      }
      case "static-payload" -> {
        host = install("fixture-production");
        host.start();
        fragment = installFile(variant("fixture-tests-fragment", "static-api",
            Map.of("Import-Package", "org.osgi.framework;version=\"[1.8,2)\""), Set.of()));
        check(!resolve(fragment), "External static API unexpectedly attached late");
        check(wiring(fragment) == null && dynamic(host) == 0, "Hook bypassed attachment failure");
        refresh(host);
        check(resolve(host, fragment), "Static API did not resolve after refresh");
        check(wiring(host)
                  .getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE)
                  .stream()
                  .anyMatch(w
                      -> "org.osgi.framework".equals(w.getCapability().getAttributes().get(
                          PackageNamespace.PACKAGE_NAMESPACE))),
            "Unrelated static API import lost");
        success(fragment, host, SEED, TESTS, 4);
      }
      case "other-effective" -> {
        Bundle ordinary = installFile(variant("fixture-ordinary-tests", "other-effective",
            Map.of("Require-Capability", "junit;effective:=custom"), Set.of()));
        check(resolve(ordinary), "Custom effective incorrectly blocked resolution");
        ordinary.loadClass("fixtures.ordinary.Seed");
        check(dynamic(ordinary) == 0, "Non-active declaration qualified");
        error(execute(ordinary, null, "fixtures.ordinary.Seed",
                  new String[] {"fixtures.ordinary.OrdinaryTest"}, false),
            "No active junit declaration");
      }
      case "wildcards" -> {
        fixtures(true);
        success(fragment, host, SEED, TESTS, 4);
        visibility(5);
        host.loadClass("fixtures.probes.Probe0");
        check(dynamic(host) == 5, "Wildcard requirements grew after package resolution");
      }
      case "version-range" -> {
        fixtures(true);
        host.loadClass(SEED);
        check(dynamic(host) == 1, "Version policy not injected");
        expectMissing(() -> host.loadClass("org.junit.Test"));
        check(wiring(host)
                  .getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE)
                  .stream()
                  .noneMatch(w
                      -> "org.junit".equals(w.getCapability().getAttributes().get(
                          PackageNamespace.PACKAGE_NAMESPACE))),
            "Incompatible version wired");
      }
      case "hook-errors" -> {
        fixtures(true);
        host.loadClass(SEED);
        for (boolean expectedFailure : List.of(false, true)) {
          AtomicInteger calls = new AtomicInteger();
          Dictionary<String, Object> props = new Hashtable<>();
          props.put(Constants.SERVICE_RANKING, Integer.MAX_VALUE);
          WeavingHook faulty = wc -> {
            if (wc.getBundleWiring() != wiring(host))
              return;
            if (calls.incrementAndGet() == 1) {
              if (expectedFailure)
                throw new WeavingException("expected hook sentinel");
              throw new IllegalStateException("unexpected hook sentinel");
            }
          };
          ServiceRegistration<WeavingHook> registration =
              context.registerService(WeavingHook.class, faulty, props);
          int offset = expectedFailure ? 2 : 0;
          try {
            try {
              host.loadClass("fixtures.probes.Probe" + offset);
              throw new AssertionError("Hook exception was swallowed");
            } catch (ClassFormatError expected) {
              check(expected.getCause() != null, "Hook failure cause lost");
            }
            FrameworkEvent event = errorEvents.poll(10, TimeUnit.SECONDS);
            check(event != null && event.getThrowable().getMessage().contains("hook sentinel"),
                "Missing expected hook error event");
            errors.remove(event);
            host.loadClass("fixtures.probes.Probe" + (offset + 1));
            check(calls.get() == (expectedFailure ? 2 : 1),
                "Wrong deny-list handling for WeavingException=" + expectedFailure);
          } finally {
            registration.unregister();
          }
        }
        check(dynamic(host) == 35, "Hook errors grew imports");
        success(fragment, host, SEED, TESTS, 4);
      }
      default -> throw new IllegalArgumentException(name);
    }
  }
  private void loadConcurrent(int offset, int count, boolean firstRace) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(count);
    CyclicBarrier start = new CyclicBarrier(count);
    CyclicBarrier weaveBarrier = new CyclicBarrier(count);
    ServiceRegistration<WeavingHook> blocker = null;
    if (firstRace) {
      // A later hook holds each callback before Equinox commits our earlier additions.
      Dictionary<String, Object> props = new Hashtable<>();
      props.put(Constants.SERVICE_RANKING, Integer.MIN_VALUE);
      blocker = context.registerService(WeavingHook.class, wc -> {
        if (wc.getBundleWiring() == wiring(host)
            && wc.getClassName().startsWith("fixtures.probes."))
          try {
            weaveBarrier.await(15, TimeUnit.SECONDS);
          } catch (Exception e) {
            throw new WeavingException("Race barrier failed", e);
          }
      }, props);
    }
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        int n = offset + i;
        futures.add(pool.submit(() -> {
          start.await(15, TimeUnit.SECONDS);
          Class<?> c = host.loadClass("fixtures.probes.Probe" + n);
          check(c.getMethod("junitType").invoke(null) == host.loadClass("org.junit.Assert"),
              "Concurrent identity");
          return null;
        }));
      }
      for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
    } finally {
      if (blocker != null)
        blocker.unregister();
      pool.shutdownNow();
      check(pool.awaitTermination(10, TimeUnit.SECONDS), "Workers did not stop");
    }
  }
  private void visibility() throws Exception {
    visibility(35);
  }
  private void visibility(long expectedCount) throws Exception {
    check(dynamic(host) == expectedCount, "Unexpected dynamic requirement count: " + dynamic(host));
    Properties probes = new Properties();
    try (InputStream in = getClass().getResourceAsStream("/reference-packages.properties")) {
      probes.load(in);
    }
    for (String pkg : probes.stringPropertyNames()) {
      Class<?> type = host.loadClass(probes.getProperty(pkg));
      Bundle actual = FrameworkUtil.getBundle(type);
      String expected = pkg.startsWith("org.hamcrest")
              && provider.getSymbolicName().equals("example.provider.junit")
          ? "example.provider.hamcrest"
          : provider.getSymbolicName();
      check(actual != null && actual.getSymbolicName().equals(expected),
          "Wrong defining bundle for " + pkg + ": " + actual);
      BundleWire wire = wiring(host)
                            .getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE)
                            .stream()
                            .filter(w
                                -> pkg.equals(w.getCapability().getAttributes().get(
                                    PackageNamespace.PACKAGE_NAMESPACE)))
                            .findFirst()
                            .orElseThrow();
      check("dynamic".equals(wire.getRequirement().getDirectives().get("resolution")),
          "Non-dynamic wire: " + pkg);
      check(
          wire.getProviderWiring().getBundle() == actual, "Wire/defining loader disagree: " + pkg);
      check(type.getClassLoader() == wire.getProviderWiring().getClassLoader(),
          "Wrong exporter loader: " + pkg);
    }
    Class<?> test = host.loadClass(TESTS[0]);
    Class<?> annotation = host.loadClass("org.junit.Test");
    check(Arrays.stream(test.getDeclaredMethods())
              .flatMap(m -> Arrays.stream(m.getDeclaredAnnotations()))
              .anyMatch(a -> a.annotationType() == annotation),
        "Annotation class identity mismatch");
    System.out.println("Verified all 35 package exporters and dynamic wires.");
  }
  private Map<String, Object> execute(Bundle target, Bundle selectedHost, String seed,
      String[] tests, boolean runnerFirst) throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("bundleId", target.getBundleId());
    if (selectedHost != null)
      args.put("hostId", selectedHost.getBundleId());
    args.put("seed", seed);
    args.put("tests", tests);
    args.put("runnerFirst", runnerFirst);
    if (target == fragment)
      args.put("listener", "fixtures.tests.CountingListener");
    ServiceReference<?> ref = null;
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (ref == null && System.nanoTime() < end) {
      ServiceReference<?>[] refs =
          context.getServiceReferences("org.osgi.service.application.ApplicationDescriptor",
              "(service.pid=example.junit.execution.tests)");
      if (refs != null && refs.length > 0)
        ref = refs[0];
      else
        Thread.sleep(10);
    }
    check(ref != null, "ApplicationDescriptor not registered");
    Object descriptor = context.getService(ref);
    ClassLoader before = Thread.currentThread().getContextClassLoader();
    ClassLoader sentinel = new ClassLoader(before) {};
    try {
      Thread.currentThread().setContextClassLoader(sentinel);
      Class<?> descriptorType = app.loadClass("org.osgi.service.application.ApplicationDescriptor");
      Object handle = descriptorType.getMethod("launch", Map.class).invoke(descriptor, args);
      Object value = app.loadClass("org.osgi.service.application.ApplicationHandle")
                         .getMethod("getExitValue", long.class)
                         .invoke(handle, 30000L);
      @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) value;
      System.out.println("APPLICATION " + result);
      check(Boolean.TRUE.equals(result.get("tcclRestored")), "Application did not restore TCCL");
      check(Thread.currentThread().getContextClassLoader() == sentinel,
          "Application changed caller TCCL");
      return result;
    } finally {
      Thread.currentThread().setContextClassLoader(before);
      context.ungetService(ref);
    }
  }
  private void success(Bundle target, Bundle selectedHost, String seed, String[] tests,
      int expected) throws Exception {
    Map<String, Object> r = execute(target, selectedHost, seed, tests, false);
    check("passed".equals(r.get("status")), "Execution failed: " + r);
    check(Integer.valueOf(expected).equals(r.get("tests")), "Wrong test count: " + r);
    check(Integer.valueOf(0).equals(r.get("failures")), "Failures: " + r);
    if (target == fragment)
      check(Integer.valueOf(expected).equals(r.get("listenerCount")),
          "Listener identity/count: " + r);
  }
  private static void error(Map<String, Object> r, String text) {
    check("error".equals(r.get("status")) && r.toString().contains(text),
        "Expected error containing " + text + ": " + r);
  }
  private void refresh(Bundle... targets) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<FrameworkEvent> event = new AtomicReference<>();
    framework.adapt(FrameworkWiring.class).refreshBundles(Arrays.asList(targets), e -> {
      event.set(e);
      done.countDown();
    });
    check(done.await(30, TimeUnit.SECONDS), "Refresh timeout");
    check(event.get().getType() == FrameworkEvent.PACKAGES_REFRESHED, "Unexpected refresh event");
  }
  private static long dynamic(Bundle b) {
    BundleWiring w = wiring(b);
    return w == null ? 0
                     : w.getRequirements(PackageNamespace.PACKAGE_NAMESPACE)
                           .stream()
                           .filter(q -> "dynamic".equals(q.getDirectives().get("resolution")))
                           .count();
  }
  private static void attached(Bundle f, Bundle h) {
    check(wiring(f) != null
            && wiring(f)
                .getRequiredWires(HostNamespace.HOST_NAMESPACE)
                .stream()
                .anyMatch(w -> w.getProviderWiring() == wiring(h)),
        "No actual selected host attachment");
  }
  private Path variant(String artifact, String label, Map<String, String> changes,
      Set<String> remove) throws Exception {
    Path out = directory.resolve(label + ".jar");
    try (JarFile source = new JarFile(bundles.resolve(artifact + ".jar").toFile())) {
      Manifest mf = new Manifest(source.getManifest());
      for (String key : remove) mf.getMainAttributes().remove(new Attributes.Name(key));
      changes.forEach((k, v) -> mf.getMainAttributes().putValue(k, v));
      try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(out), mf)) {
        for (JarEntry entry : Collections.list(source.entries())) {
          if (entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF"))
            continue;
          jar.putNextEntry(new JarEntry(entry.getName()));
          try (InputStream in = source.getInputStream(entry)) {
            in.transferTo(jar);
          }
          jar.closeEntry();
        }
      }
    }
    return out;
  }
  private void inspectArtifacts() throws Exception {
    for (String artifact :
        List.of("fixture-tests-fragment", "fixture-ordinary-tests", "fixture-production"))
      try (JarFile jar = new JarFile(bundles.resolve(artifact + ".jar").toFile())) {
        Attributes a = jar.getManifest().getMainAttributes();
        String imports = a.getValue("Import-Package");
        check(imports == null
                || (!imports.contains("org.junit") && !imports.contains("junit.framework")
                    && !imports.contains("org.hamcrest")),
            "Static JUnit/Hamcrest import");
        if (artifact.equals("fixture-ordinary-tests"))
          check(imports != null && imports.contains("org.osgi.framework"),
              "Unrelated static API import lost during manifest generation");
        check(a.getValue("DynamicImport-Package") == null, "Predeclared dynamic import");
        check(a.getValue("Require-Bundle") == null, "Require-Bundle bypass");
        String cp = a.getValue("Bundle-ClassPath");
        check(cp == null || cp.equals("."), "Nested bundle class path");
        for (JarEntry e : Collections.list(jar.entries()))
          check(!e.getName().startsWith("org/junit/") && !e.getName().startsWith("junit/")
                  && !e.getName().startsWith("org/hamcrest/") && !e.getName().endsWith(".jar"),
              "Embedded dependency " + e.getName());
        if (artifact.equals("fixture-tests-fragment"))
          check(jar.getEntry("fixtures/production/Calculator.class") == null,
              "Embedded production class");
        if (!artifact.equals("fixture-production"))
          check(a.getValue("Require-Capability").contains("junit;effective:=active"),
              "Wrong active contract");
      }
    if (provider != null
        && provider.getSymbolicName().equals("org.apache.servicemix.bundles.junit")) {
      Properties expected = new Properties();
      try (InputStream in = getClass().getResourceAsStream("/reference-packages.properties")) {
        expected.load(in);
      }
      Set<String> exports = new HashSet<>();
      for (BundleCapability cap :
          wiring(provider).getCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
        String pkg = (String) cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
        exports.add(pkg);
        check(new Version(pkg.startsWith("org.hamcrest") ? "1.3.0" : "4.13.2")
                  .equals(cap.getAttributes().get("version")),
            "Reference export version drift: " + pkg);
      }
      check(exports.equals(expected.stringPropertyNames()), "Reference package inventory drift");
      check(
          new Version("4.13.2.1").equals(provider.getVersion()), "ServiceMix bundle version drift");
    }
    for (BundleCapability cap :
        framework.adapt(BundleWiring.class).getCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
      String pkg = String.valueOf(cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE));
      check(!pkg.startsWith("org.junit") && !pkg.startsWith("junit.")
              && !pkg.startsWith("org.hamcrest"),
          "System exported test dependency");
    }
  }
  @FunctionalInterface
  private interface Load {
    Object get() throws Exception;
  }
  private static void expectMissing(Load load) throws Exception {
    try {
      load.get();
      throw new AssertionError("Unexpected class visibility");
    } catch (ClassNotFoundException expected) {
    }
  }
  private static void check(boolean condition, String message) {
    if (!condition)
      throw new AssertionError(message);
  }
}
