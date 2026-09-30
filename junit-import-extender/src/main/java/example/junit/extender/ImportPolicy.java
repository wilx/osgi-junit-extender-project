package example.junit.extender;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.osgi.framework.VersionRange;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleWiring;

/** Immutable, provider-neutral package policy. Invalid configuration fails activation. */
final class ImportPolicy {
  private static final Pattern PACKAGE =
      Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*(?:\\.\\*)?");
  static final String DEFAULTS = String.join(",", "junit.extensions;version=\"[4.13.2,5)\"",
      "junit.framework;version=\"[4.13.2,5)\"", "junit.runner;version=\"[4.13.2,5)\"",
      "junit.textui;version=\"[4.13.2,5)\"", "org.hamcrest;version=\"[1.3,2)\"",
      "org.hamcrest.core;version=\"[1.3,2)\"", "org.hamcrest.internal;version=\"[1.3,2)\"",
      "org.junit;version=\"[4.13.2,5)\"", "org.junit.experimental;version=\"[4.13.2,5)\"",
      "org.junit.experimental.categories;version=\"[4.13.2,5)\"",
      "org.junit.experimental.max;version=\"[4.13.2,5)\"",
      "org.junit.experimental.results;version=\"[4.13.2,5)\"",
      "org.junit.experimental.runners;version=\"[4.13.2,5)\"",
      "org.junit.experimental.theories;version=\"[4.13.2,5)\"",
      "org.junit.experimental.theories.internal;version=\"[4.13.2,5)\"",
      "org.junit.experimental.theories.suppliers;version=\"[4.13.2,5)\"",
      "org.junit.function;version=\"[4.13.2,5)\"", "org.junit.internal;version=\"[4.13.2,5)\"",
      "org.junit.internal.builders;version=\"[4.13.2,5)\"",
      "org.junit.internal.management;version=\"[4.13.2,5)\"",
      "org.junit.internal.matchers;version=\"[4.13.2,5)\"",
      "org.junit.internal.requests;version=\"[4.13.2,5)\"",
      "org.junit.internal.runners;version=\"[4.13.2,5)\"",
      "org.junit.internal.runners.model;version=\"[4.13.2,5)\"",
      "org.junit.internal.runners.rules;version=\"[4.13.2,5)\"",
      "org.junit.internal.runners.statements;version=\"[4.13.2,5)\"",
      "org.junit.matchers;version=\"[4.13.2,5)\"", "org.junit.rules;version=\"[4.13.2,5)\"",
      "org.junit.runner;version=\"[4.13.2,5)\"",
      "org.junit.runner.manipulation;version=\"[4.13.2,5)\"",
      "org.junit.runner.notification;version=\"[4.13.2,5)\"",
      "org.junit.runners;version=\"[4.13.2,5)\"", "org.junit.runners.model;version=\"[4.13.2,5)\"",
      "org.junit.runners.parameterized;version=\"[4.13.2,5)\"",
      "org.junit.validator;version=\"[4.13.2,5)\"");
  private final List<Clause> clauses;
  private ImportPolicy(List<Clause> clauses) {
    this.clauses = List.copyOf(clauses);
  }

  static ImportPolicy parse(String configuration) {
    List<Clause> clauses = new ArrayList<>();
    for (String text : split(configuration == null ? DEFAULTS : configuration, ',')) {
      List<String> parts = split(text, ';');
      if (parts.size() != 2 || !parts.get(1).startsWith("version="))
        throw new IllegalArgumentException(
            "Each import must contain one package and only a version range: " + text);
      String name = parts.get(0);
      if (!PACKAGE.matcher(name).matches() || name.startsWith("java."))
        throw new IllegalArgumentException("Invalid package pattern: " + name);
      String version = parts.get(1).substring("version=".length()).trim();
      if (version.startsWith("\"") && version.endsWith("\""))
        version = version.substring(1, version.length() - 1);
      VersionRange range = new VersionRange(version);
      if (range.getRight() == null || range.isEmpty())
        throw new IllegalArgumentException("A nonempty bounded version range is required: " + text);
      for (Clause prior : clauses)
        if (overlap(prior.name, name))
          throw new IllegalArgumentException("Overlapping package patterns: " + name);
      String clause = name + ";version=\"" + range + "\"";
      String filter = "(&(" + PackageNamespace.PACKAGE_NAMESPACE + "=" + name + ")"
          + range.toFilterString("version") + ")";
      clauses.add(new Clause(name, clause, FilterKey.of(filter)));
    }
    if (clauses.isEmpty())
      throw new IllegalArgumentException("At least one import is required");
    return new ImportPolicy(clauses);
  }

  private static boolean overlap(String a, String b) {
    return a.equals(b) || (a.endsWith(".*") && b.startsWith(a.substring(0, a.length() - 1)))
        || (b.endsWith(".*") && a.startsWith(b.substring(0, b.length() - 1)));
  }

  private static List<String> split(String text, char separator) {
    List<String> result = new ArrayList<>();
    boolean quoted = false;
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"')
        quoted = !quoted;
      else if (c == separator && !quoted) {
        result.add(text.substring(start, i).trim());
        start = i + 1;
      }
    }
    if (quoted)
      throw new IllegalArgumentException("Unclosed quote in import configuration");
    result.add(text.substring(start).trim());
    return result;
  }

  int size() {
    return clauses.size();
  }

  void addMissing(BundleWiring wiring, List<String> additions) {
    Set<String> committed = new HashSet<>();
    List<BundleRequirement> requirements =
        wiring.getRequirements(PackageNamespace.PACKAGE_NAMESPACE);
    // A concurrent refresh can dispose of the wiring while this callback is running.
    if (requirements == null)
      return;
    for (BundleRequirement requirement : requirements) {
      if ("dynamic".equals(requirement.getDirectives().get("resolution"))) {
        committed.add(FilterKey.of(requirement.getDirectives().get("filter")));
      }
    }
    for (Clause clause : clauses) {
      if (!committed.contains(clause.filterKey) && !additions.contains(clause.header))
        additions.add(clause.header);
    }
  }
  private record Clause(String name, String header, String filterKey) {}
}
