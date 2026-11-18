package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PackageDependencyGuardTest {

    private static final List<String[]> AUDITED_PAIRS = buildAuditedPairs();

    private static final Map<String, Set<String>> AUDITED_HOTSPOT_EDGES = buildAuditedHotspotEdges();

    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?org\\.workflowsim\\.([\\w]+(?:\\.[\\w]+)*?)\\.(\\*|[A-Z]\\w*)\\s*;",
            Pattern.MULTILINE);
    private static final Pattern INLINE =
            Pattern.compile("\\borg\\.workflowsim\\.((?:[a-z]\\w*\\.)*[a-z]\\w*)\\.[A-Z]\\w*");

    private static Map<String, Set<String>> dependencies;
    private static Set<String> realPackages;

    @BeforeAll
    static void scanSourceTree() throws IOException {
        Path root = sourceRoot();
        realPackages = new TreeSet<String>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory).forEach(dir -> realPackages.add(packageNameOf(root, dir)));
        }
        dependencies = new TreeMap<String, Set<String>>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                scanFile(root, file);
            }
        }
    }

    @Test
    void bidirectionalPairsStayAtTheAuditedInventory() {
        Set<String> actual = new TreeSet<String>();
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            for (String target : entry.getValue()) {
                if (dependencies.containsKey(target) && dependencies.get(target).contains(entry.getKey())) {
                    actual.add(pairKey(entry.getKey(), target));
                }
            }
        }
        Set<String> expected = new TreeSet<String>();
        for (String[] pair : AUDITED_PAIRS) {
            expected.add(pairKey(pair[0], pair[1]));
        }
        assertEquals(
                expected,
                actual,
                "The bidirectional package-pair inventory changed (AUD-F16): a new pair means"
                        + " a new dependency cycle -- decouple it instead; a removed pair means"
                        + " progress -- update AUDITED_PAIRS to lock the new inventory.");
    }

    @Test
    void hotspotOutgoingEdgesStayAtTheAuditedAllowlist() {
        for (Map.Entry<String, Set<String>> entry : AUDITED_HOTSPOT_EDGES.entrySet()) {
            Set<String> actual = dependencies.get(entry.getKey());
            if (actual == null) {
                actual = new TreeSet<String>();
            }
            assertEquals(
                    new TreeSet<String>(entry.getValue()),
                    actual,
                    "Outgoing package edges of hotspot " + entry.getKey()
                            + " changed (AUD-F16): new outgoing dependencies from a cycle"
                            + " hotspot are rejected; removed edges are progress -- update the"
                            + " allowlist.");
        }
    }

    private static void scanFile(Path root, Path file) throws IOException {
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String pkg = packageNameOf(root, file.getParent());
        Set<String> targets = new TreeSet<String>();
        Matcher matcher = IMPORT.matcher(source);
        while (matcher.find()) {
            targets.add("org.workflowsim." + matcher.group(1));
        }
        matcher = INLINE.matcher(source);
        while (matcher.find()) {
            targets.add("org.workflowsim." + matcher.group(1));
        }
        for (String target : targets) {
            if (realPackages.contains(target)
                    && !target.equals(pkg)
                    && !pkg.startsWith(target + ".")
                    && !target.startsWith(pkg + ".")) {
                Set<String> edges = dependencies.get(pkg);
                if (edges == null) {
                    edges = new TreeSet<String>();
                    dependencies.put(pkg, edges);
                }
                edges.add(target);
            }
        }
    }

    private static String packageNameOf(Path root, Path dir) {
        Path relative = root.relativize(dir.toAbsolutePath().normalize());
        StringBuilder name = new StringBuilder("org.workflowsim");
        for (Path part : relative) {
            name.append('.').append(part.toString());
        }
        return name.toString();
    }

    private static Path sourceRoot() throws IOException {
        Path base = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path root = base.resolve("src/main/java/org/workflowsim");
        if (!Files.isDirectory(root)) {
            throw new IOException("Simulator source root not found at " + root
                    + " (expected surefire working directory to be the simulator module)");
        }
        return root;
    }

    private static String pairKey(String first, String second) {
        String[] ordered = new String[] {first, second};
        java.util.Arrays.sort(ordered);
        return String.format(Locale.ROOT, "%s <-> %s", ordered[0], ordered[1]);
    }

    private static List<String[]> buildAuditedPairs() {
        List<String[]> pairs = new ArrayList<String[]>();
        pairs.add(new String[] {"org.workflowsim.data.v2", "org.workflowsim.utils"});
        pairs.add(new String[] {"org.workflowsim.experiment.registry", "org.workflowsim.utils"});
        pairs.add(new String[] {"org.workflowsim.failure", "org.workflowsim.utils"});
        return pairs;
    }

    private static Map<String, Set<String>> buildAuditedHotspotEdges() {
        Map<String, Set<String>> edges = new HashMap<String, Set<String>>();
        edges.put(
                "org.workflowsim.utils",
                new TreeSet<String>(java.util.Arrays.asList(
                        "org.workflowsim.data",
                        "org.workflowsim.data.v2",
                        "org.workflowsim.experiment.registry",
                        "org.workflowsim.failure",
                        "org.workflowsim.platform")));
        edges.put(
                "org.workflowsim.scheduling",
                new TreeSet<String>(java.util.Arrays.asList(
                        "org.workflowsim.platform", "org.workflowsim.rl", "org.workflowsim.utils")));
        edges.put(
                "org.workflowsim.rl",
                new TreeSet<String>(java.util.Arrays.asList(
                        "org.workflowsim.exception",
                        "org.workflowsim.experiment",
                        "org.workflowsim.platform",
                        "org.workflowsim.utils")));
        edges.put(
                "org.workflowsim.experiment.registry",
                new TreeSet<String>(java.util.Arrays.asList(
                        "org.workflowsim.data",
                        "org.workflowsim.failure",
                        "org.workflowsim.scheduling",
                        "org.workflowsim.utils")));
        return edges;
    }
}
