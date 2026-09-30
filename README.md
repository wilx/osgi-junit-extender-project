# OSGi JUnit package-import extender

This Maven reactor implements the explicitly selected contract:

```text
Test bundle or fragment: Require-Capability: junit;effective:=active
Extender:                Provide-Capability: junit
```

The extender adds package visibility through a public OSGi `WeavingHook`. The separate Eclipse application fixture owns test selection, bootstrap ordering, TCCL, runner invocation, and results. No production JUnit dependency is required.

Verified on 2026-09-30: **4 unit tests and 28 isolated Equinox scenarios passed**, with zero failures, errors, or skips. See `verification/result.json`, the final Maven log, and the preserved per-scenario logs.

## Build and run

Use JDK 17 and the included Maven Wrapper, pinned to Maven 3.9.11 with its distribution SHA-256:

```sh
cd /home/wilx/osgi-junit-extender-project
./mvnw -B -ntp clean verify
```

The verification run used an isolated Maven cache and wrapper installation:

```sh
MAVEN_USER_HOME="$PWD/.tools/wrapper" ./mvnw -Dmaven.repo.local=.m2 -B -ntp clean verify
```

Run a focused scenario set with `-Dit.scenarios=late,ordering,alternative`. The default is `all`; unknown names fail. Surefire runs extender unit tests; Failsafe runs the framework scenarios during `integration-test` and checks their results during `verify`. `mvn test` alone does not execute the framework matrix.

Each scenario starts a fresh child JVM with only the packaged harness JAR and Equinox JAR on its class path. Each has fresh framework storage, bounded synchronization, framework error capture, and shutdown in `finally`. Detailed child output is under `it-harness/target/case-*/child.log`; Maven reports are under the respective `target/surefire-reports` and `target/failsafe-reports` directories. Preserved evidence is in `verification/`.

## Meaning and enforcement of active

`effective:=active` excludes this requirement from normal framework resolution. It does **not** start the extender, wait for its hook, or automatically enforce an activation dependency. The omitted resolution directive still defaults to mandatory, but the framework resolver ignores this non-resolve-effective requirement altogether. The literal custom namespace remains `junit`.

Consequently, consumers can resolve and fragments can attach even when the extender is absent. Application execution explicitly requires a registered hook from an ACTIVE bundle whose declared `junit` capability matches a consumer's `effective:=active` declaration. Capability matching uses `BundleRequirement.matches`, including filters. A bare `Provide-Capability: junit` has no attributes; a filter demanding an absent attribute does not match it. Optional active declarations also opt in if they match. Omitted effective, `effective:=resolve`, and other effective values do not opt in to this implementation.

The hook checks declarations on `BundleRevision`, because active requirements are absent from runtime `BundleWiring.getRequirements`. For fragments it follows actual provided `osgi.wiring.host` wires from the woven host to the attached fragment revisions. Installed but unattached fragments do not qualify the host. Updated fragments can retain old attached revisions until refresh; eligibility follows those actual revisions. No eligibility cache, bundle tracker, or event ordering assumption is used.

These distinctions follow the [OSGi module specification](https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html), [BundleWiring API](https://docs.osgi.org/javadoc/osgi.core/8.0.0/org/osgi/framework/wiring/BundleWiring.html), and [BundleRequirement matching API](https://docs.osgi.org/javadoc/osgi.core/8.0.0/org/osgi/framework/wiring/BundleRequirement.html).

## Verified attachment behavior and boundaries

On the pinned Equinox build, the active fragment attaches to an already ACTIVE host after its normal production class has loaded. The test verifies that the host remains ACTIVE and retains the **same wiring object and class loader**, without a refresh. Both early attachment and a separately executed refresh path are also tested.

This outcome depends on the fragment's other requirements. A mandatory, resolve-effective `junit` requirement supplied by the external extender prevents late attachment. An unrelated mandatory static API import from an external bundle does too. Both controls succeed after refresh. A weaving hook cannot repair an unresolved fragment's attachment.

The exact implementation is [ModuleResolver.isEffective](https://github.com/eclipse-equinox/equinox/blob/73815d3f63a3fd5e2614138ef5789c371f9ed85b/bundles/org.eclipse.osgi/container/src/org/eclipse/osgi/container/ModuleResolver.java#L936-L939) and [DynamicFragments.failToWire / resolveNonPayLoadFragments](https://github.com/eclipse-equinox/equinox/blob/73815d3f63a3fd5e2614138ef5789c371f9ed85b/bundles/org.eclipse.osgi/container/src/org/eclipse/osgi/container/ModuleResolver.java#L1339-L1501): active requirements are skipped, while effective payload requirements must use the host or its fragments as providers.

The fragment excludes generated `java.*` imports because they also caused the external-payload restriction in the initial experiment. Java platform classes remain available through normal framework Java delegation. The final `*` in its bnd import instructions preserves unrelated API imports; the ordinary fixture exercises an actual generated static OSGi API import. Consumers needing other external static fragment imports must use an appropriate resolution/refresh workflow rather than deleting those dependencies.

## Modules and artifact construction

| Module | Purpose and dependencies |
| --- | --- |
| `junit-import-extender` | Bundle activator and hook; OSGi Core API provided, JUnit Jupiter test scope only |
| `fixture-production` | Production bundle, no dependencies; package-private calculator operation |
| `fixture-tests-fragment` | Executable JUnit 4 fragment; direct ServiceMix and production dependencies, both provided |
| `fixture-ordinary-tests` | Ordinary active consumer; ServiceMix and OSGi API provided |
| `execution-app-fixture` | Real `IApplication` extension; Equinox application and OSGi APIs provided |
| `alternative-junit-provider` | Test provider wrapping upstream JUnit 4.13.2 under a different bundle identity |
| `alternative-hamcrest-provider` | Separate test provider wrapping Hamcrest Core 1.3 |
| `it-harness` | Child-process framework harness and outer Jupiter/Failsafe tests |

Bundle modules use JAR packaging, Felix Bundle Plugin 6.0.0's `manifest` goal at `process-classes`, and Maven Jar Plugin 3.4.2 at `package`. Manifests are generated outside `target/classes`, preventing old generated headers from feeding back into bnd analysis. Manifest analysis uses `rebuildBundle=false`, no embedding instructions, and only the module's own compiled output. Executable fragment test classes deliberately live in `src/main/java`; ordinary Maven test compilation would not put them into the fragment JAR.

The harness inspects packaged artifacts to reject embedded JUnit/Hamcrest/production classes, nested dependency JARs, predeclared dynamic imports, static JUnit/Hamcrest imports, and Require-Bundle shortcuts. The production host uses `fragment-attachment:=always`. The fragment targets host versions `[1.0.0,3.0.0)`.

Dependency Plugin 3.8.1 stages packaged artifacts at `pre-integration-test`. The harness separately installs the provider into Equinox; a provided Maven dependency is compile-time access only. Alternative-provider modules intentionally unpack provider bytecode; consumer modules do not.

## Import policy and class identity

The immutable default policy is the exact 35-package inventory exported by `org.apache.servicemix.bundles:org.apache.servicemix.bundles.junit:4.13.2_1`. Its OSGi bundle version is **4.13.2.1**. There are 28 `org.junit` packages and four legacy `junit.*` packages at **4.13.2**, plus three Hamcrest packages at **1.3.0**. Each default clause is an exact package import with `[4.13.2,5)` or `[1.3,2)` respectively. No bundle-symbolic-name or bundle-version constraint is injected.

The full inventory and one runtime class probe per package are in `it-harness/src/main/resources/reference-packages.properties`. Tests compare the inventory and versions against the actual installed reference bundle, then inspect all 35 dynamic package wires and defining loaders.

Set framework property `example.junit.extender.imports` before activation to replace the defaults, for example:

```text
org.junit;version="[4.13.2,5)",org.junit.*;version="[4.13.2,5)",junit.*;version="[4.13.2,5)",org.hamcrest;version="[1.3,2)",org.hamcrest.*;version="[1.3,2)"
```

Root packages are separate from subpackage patterns. Configuration requires nonempty bounded version ranges and permits only the package and version attribute. Empty, malformed, overlapping, unrestricted `*`, Java-package, or provider-specific clauses fail activation. Runtime scenarios verify exact-root configuration, wildcard configuration, and incompatible-version rejection. `example.junit.extender.enabled=false` leaves the capability present but disables hook registration for diagnostics.

Dynamic resolution chooses compatible exporters according to the framework's resolver and existing class-space/uses constraints. The extender does not select a vendor or guarantee arbitrary mixed providers are compatible. Tests cover ServiceMix alone, alternative JUnit plus separate Hamcrest alone, and a pinned multiple-provider setup selecting ServiceMix. Annotation, runner, custom runner, listener, JUnit 3, and test-class identities are exercised through their target loaders.

## Callback, bootstrap, and concurrency contract

The hook's callback excludes its own bundle, reads host and attached-fragment declarations, and appends missing clauses to `WovenClass.getDynamicImports()`. It does not transform bytecode, load consumer/JUnit classes, log, resolve, start, refresh, or wait on locks. Helpers and configuration are initialized before registration. Unexpected exceptions are allowed to reach the framework; they are not swallowed.

Committed dynamic requirements are compared by normalized generated LDAP filters. No processed flag is set before the framework commits additions. The installed manifest remains unchanged. Additions affect the host's runtime package visibility, including production classes and other fragments; there is no per-fragment import isolation.

**Before concurrent or recursively complex test loading, the application/caller must complete one fresh, simple local class definition through the target loader after hook registration and eligibility.** The fixture uses a dependency-free seed class. It then checks that imports exist, loads named test classes explicitly through that loader, and reflectively calls `JUnitCore.run(Class<?>[])`. Loading JUnitCore first does not trigger local weaving and fails, even with TCCL set correctly.

This bootstrap requirement is substantive. A deterministic 16-thread first-definition race produces **560 raw requirements for 35 distinct clauses**: every callback can return before any additions are committed. Subsequent definitions do not cause further growth. Equinox [ModuleWiring.addDynamicImports](https://github.com/eclipse-equinox/equinox/blob/73815d3f63a3fd5e2614138ef5789c371f9ed85b/bundles/org.eclipse.osgi/container/src/org/eclipse/osgi/container/ModuleWiring.java#L418-L446) appends those requirements without deduplicating them. This project does **not** claim exact deduplication for arbitrary unprepared first-definition races, simultaneous independent bootstrap calls, or nested first definitions on the same unprepared wiring. The stress scenario records this limitation instead of treating duplicates as a supported execution order.

After controlled bootstrap, 64 distinct classes loaded in four concurrent waves leave exactly 35 requirements. The reentrant test demonstrably enters nested callbacks on a second, previously unprepared eligible wiring; both receive imports and execute tests. There is no blanket recursion guard that drops nested consumers. Equinox's own [same-class-name recursion safeguard](https://github.com/eclipse-equinox/equinox/blob/73815d3f63a3fd5e2614138ef5789c371f9ed85b/bundles/org.eclipse.osgi/container/src/org/eclipse/osgi/internal/weaving/WeavingHookConfigurator.java#L65-L103) is not used as an extender eligibility mechanism.

The [WeavingHook API](https://docs.osgi.org/javadoc/osgi.core/8.0.0/org/osgi/framework/hooks/weaving/WeavingHook.html) defines class-load failure and deny-list behavior. The error scenario verifies that an unexpected hook exception disables that registration, while `WeavingException` fails the load without disabling it. Only specifically expected framework errors are consumed; other errors fail the scenario.

## Application and lifecycle behavior

The fixture is registered through `org.eclipse.core.runtime.applications` and launched via its real `ApplicationDescriptor`, with application ID `example.junit.execution.tests`. An embedded framework is sufficient here because the test invokes Equinox's application registry and lifecycle rather than calling the fixture directly. This does not test a graphical Eclipse product or a separate native Eclipse launcher.

Arguments include `bundleId`, optional explicit `hostId`, `seed`, and a nonempty `tests` array of class names. For an ordinary bundle the application selects its wiring loader. For fragments it follows actual host wires, including still-in-use old revisions; multiple hosts require explicit selection. Fragments have no independent loader. The application saves TCCL, sets it to that loader, bootstraps imports, loads tests as Class objects, invokes the runner without static JUnit application types, extracts results reflectively, and restores TCCL in `finally` on success and error. TCCL alone cannot fix a test class's defining-loader package visibility.

Stopping the extender unregisters the hook but does not revoke already committed imports or wires. Restart examines current wiring without stale positive or negative caches. A seed loaded while the hook was stopped cannot be woven again: use a fresh local seed or refresh the host. Previously defined/linkage-failed test classes may require refresh. Fragment update/uninstall likewise does not immediately detach old revisions. Refresh discards old wiring and class identities; newly woven classes are re-evaluated. Refresh is owned by the application/harness, uses `FrameworkWiring.refreshBundles`, and waits for `PACKAGES_REFRESHED` with a deadline; its dependency closure can affect more bundles than the requested host. No refresh is scheduled from `weave()`.

## Verification matrix

| Scenarios | Measurable assertions |
| --- | --- |
| `early`, `late`, `refresh` | Actual attachment wire; four successful JUnit tests; package-private production access; late preserves wiring/loader; refresh replaces class identity |
| `ordinary` | Two successful tests, generated unrelated static API import, 35 dynamic requirements |
| `no-hook`, `absent-extender`, `missing-provider` | Active attachment succeeds; missing readiness or missing package exporter fails at the proper stage |
| `nonqualifying`, `unattached`, `filters`, `other-effective` | Only matching active declarations on actual attachments qualify; optional active is explicitly covered |
| `resolve-control`, `static-payload` | Late attachment fails for effective external payloads; refresh works; missing mandatory capability fails resolution |
| `ordering`, `restart` | Runner-first failure, fresh-seed recovery, no retroactive weaving, stable imports across restart |
| `update`, `uninstall` | Old attached declarations remain authoritative; refresh removes stale eligibility/imports; host update creates a new loader |
| `concurrent`, `recursive`, `first-race` | Exact stable counts after bootstrap, genuine nested callbacks, bounded first-race diagnostic |
| `alternative`, `multiple-providers` | All reference package probes and intended exporter wires/class identities |
| `multiple-hosts` | Two host wires, mandatory explicit selection, distinct test Class objects |
| `failures` | Intentional one-test failure with useful message, discovery error, legacy JUnit 3 success; TCCL restored throughout |
| `configuration`, `version-range`, `wildcards` | Configured clause counts, version rejection, separate roots and wildcard subpackages |
| `hook-errors` | Framework class-load failures, unexpected exception deny-listing, WeavingException retained registration |

Every application execution checks TCCL restoration. Successful suites assert a nonzero exact test count and zero failures. The intentional failure is an expected assertion of reporting behavior, not an ignored test failure. Child class paths are checked for absent JUnit/Hamcrest, boot parent loading is constrained, and framework system exports are checked for leakage.

## Pinned versions and evidence

| Component | Version |
| --- | --- |
| Equinox framework | Maven `3.24.300`; OSGi `3.24.300.v20260721-1251` |
| Equinox application / registry / common | `1.7.600` / `3.12.600` / `3.20.300` |
| ServiceMix reference | Maven `4.13.2_1`; OSGi `4.13.2.1` |
| Alternative upstream providers | JUnit `4.13.2`; Hamcrest Core `1.3` |
| Verification JDK | OpenJDK `17.0.20.1+1`; source release 17 |
| Maven / Wrapper | `3.9.11` / `3.3.4`, only-script distribution |
| Compiler / Surefire / Failsafe | `3.14.1` / `3.5.4` / `3.5.4` |
| Outer test API | JUnit Jupiter `5.13.4` |

Maven Central's framework metadata was rechecked on 2026-09-30 and saved in `verification/equinox-maven-metadata.xml`. The selected framework declares JavaSE 1.8 or JavaSE/compact1 1.8, despite being built with JDK 25; the application stack and this implementation use Java 17. Its manifest identifies source commit `73815d3f63a3fd5e2614138ef5789c371f9ed85b`.

The regression-first build used an empty extender activator. It demonstrated successful no-refresh attachment followed by application failure `JUnit weaving hook is not registered`, with TCCL restored. That failing build is preserved in `verification/first-regression.log`. The final report and logs distinguish actual passing runtime evidence from the documented concurrency boundary. Portability beyond the pinned Equinox/application stack and arbitrary concurrent lifecycle mutations remain unverified.
