package example.junit.it;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

final class EquinoxIT {
  private static final List<String> SCENARIOS =
      List.of("early", "late", "refresh", "ordinary", "no-hook", "absent-extender",
          "missing-provider", "nonqualifying", "resolve-control", "filters", "ordering", "restart",
          "update", "uninstall", "concurrent", "recursive", "first-race", "alternative",
          "multiple-providers", "multiple-hosts", "failures", "configuration", "unattached",
          "static-payload", "other-effective", "version-range", "hook-errors", "wildcards");
  @TestFactory
  Stream<DynamicTest> scenarios() {
    String selected = System.getProperty("it.scenarios", "all");
    Set<String> wanted = new HashSet<>(Arrays.asList(selected.split(",")));
    List<String> chosen =
        SCENARIOS.stream().filter(s -> wanted.contains("all") || wanted.contains(s)).toList();
    assertFalse(chosen.isEmpty(), "No matching scenarios: " + selected);
    if (!wanted.contains("all"))
      assertTrue(SCENARIOS.containsAll(wanted), "Unknown scenario: " + selected);
    return chosen.stream().map(s -> DynamicTest.dynamicTest(s, () -> runChild(s)));
  }
  private static void runChild(String scenario) throws Exception {
    Path target = Path.of("target").toAbsolutePath();
    Path directory = Files.createTempDirectory(target, "case-" + scenario + "-");
    Path log = directory.resolve("child.log");
    String cp = target.resolve("it-harness-1.0.0-SNAPSHOT.jar") + java.io.File.pathSeparator
        + target.resolve("bundles/framework.jar");
    Process child =
        new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", cp, "example.junit.it.ScenarioMain", scenario,
            target.resolve("bundles").toString(), directory.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(child.waitFor(120, TimeUnit.SECONDS), "Scenario timed out: " + log);
      assertEquals(0, child.exitValue(),
          () -> "Scenario " + scenario + " failed; " + log + "\n" + read(log));
      assertTrue(
          read(log).contains("SCENARIO PASSED " + scenario), "Missing completion marker: " + log);
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }
  private static String read(Path log) {
    try {
      return Files.readString(log);
    } catch (Exception e) {
      return e.toString();
    }
  }
}
