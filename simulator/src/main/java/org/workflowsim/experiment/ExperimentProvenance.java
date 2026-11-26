package org.workflowsim.experiment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Code and execution provenance recorded in every experiment manifest. */
public final class ExperimentProvenance {

    static final String SCHEMA_V3 = "workflowsim-provenance-v3";
    static final String SCHEMA_V4 = "workflowsim-provenance-v4";
    private static final String CORE_GROUP_ID = "org.workflowsim";
    private static final String CORE_ARTIFACT_ID = "workflowsim";
    private static final String CORE_VERSION = "1.0";
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final long GIT_STATUS_TIMEOUT_SECONDS = 30L;

    private ExperimentProvenance() {}

    static Map<String, Object> capture() {
        return capture(null);
    }

    static Map<String, Object> capture(ExperimentEvidenceContext context) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("schema", SCHEMA_V4);
        values.put("core", componentIdentity(SimulationRunner.class, CORE_GROUP_ID, CORE_ARTIFACT_ID, CORE_VERSION));
        values.put("study", context == null ? null : context.asMap());

        Map<String, Object> execution = new LinkedHashMap<String, Object>();
        execution.put("workingDirectory", System.getProperty("user.dir"));
        Path moduleRoot = moduleRoot(SimulationRunner.class);
        String commit = gitCommit(moduleRoot);
        execution.put("gitCommit", commit);
        execution.put("gitDirty", commit == null ? null : gitDirty(moduleRoot));
        values.put("execution", execution);
        return values;
    }

    static Map<String, Object> componentIdentity(Class<?> anchor, String groupId, String artifactId, String version) {
        if (anchor == null || isBlank(groupId) || isBlank(artifactId) || isBlank(version)) {
            throw new IllegalArgumentException("Component anchor and Maven coordinates are required");
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("groupId", groupId);
        values.put("artifactId", artifactId);
        values.put("version", version);
        values.put("anchorClass", anchor.getName());
        return values;
    }

    /**
     * Returns the 40-hex commit checked out in the Git repository or worktree enclosing {@code start}, read
     * directly from the Git metadata; {@code null} when there is no enclosing checkout or HEAD cannot be resolved.
     */
    public static String gitCommit(Path start) {
        Path checkout = checkoutRoot(start);
        if (checkout == null) {
            return null;
        }
        try {
            Path gitDir = gitDirectory(checkout.resolve(".git"));
            if (gitDir == null) {
                return null;
            }
            Path commonDir = commonDirectory(gitDir);
            String head = firstLine(gitDir.resolve("HEAD"));
            for (int depth = 0; head != null && depth < 8; depth++) {
                if (COMMIT.matcher(head).matches()) {
                    return head;
                }
                if (!head.startsWith("ref:")) {
                    return null;
                }
                head = resolveRef(gitDir, commonDir, head.substring(4).trim());
            }
            return null;
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * Returns whether {@code git status --porcelain} reports changes in the checkout enclosing {@code start};
     * {@code null} when there is no checkout or Git cannot answer within the timeout.
     */
    public static Boolean gitDirty(Path start) {
        Path checkout = checkoutRoot(start);
        if (checkout == null) {
            return null;
        }
        Path stdout = null;
        Path stderr = null;
        try {
            stdout = Files.createTempFile("wfledger-git-status", ".out");
            stderr = Files.createTempFile("wfledger-git-status", ".err");
            ProcessBuilder builder = new ProcessBuilder("git", "status", "--porcelain")
                    .directory(checkout.toFile())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile());
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            Process process = builder.start();
            if (!process.waitFor(GIT_STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return Boolean.valueOf(Files.size(stdout) > 0L);
        } catch (IOException exception) {
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading the Git working-tree status", exception);
        } finally {
            deleteQuietly(stdout);
            deleteQuietly(stderr);
        }
    }

    private static Path checkoutRoot(Path start) {
        if (start == null) {
            return null;
        }
        for (Path directory = start.toAbsolutePath().normalize();
                directory != null;
                directory = directory.getParent()) {
            if (Files.exists(directory.resolve(".git"))) {
                return directory;
            }
        }
        return null;
    }

    private static Path gitDirectory(Path dotGit) throws IOException {
        if (Files.isDirectory(dotGit)) {
            return dotGit;
        }
        String pointer = firstLine(dotGit);
        if (pointer == null || !pointer.startsWith("gitdir:")) {
            return null;
        }
        Path target = dotGit.getParent().resolve(pointer.substring(7).trim()).normalize();
        return Files.isDirectory(target) ? target : null;
    }

    private static Path commonDirectory(Path gitDir) throws IOException {
        String common = firstLine(gitDir.resolve("commondir"));
        return common == null ? gitDir : gitDir.resolve(common).normalize();
    }

    private static String resolveRef(Path gitDir, Path commonDir, String ref) throws IOException {
        if (ref.isEmpty() || ref.contains("..")) {
            return null;
        }
        for (Path base : Arrays.asList(gitDir, commonDir)) {
            String loose = firstLine(base.resolve(ref));
            if (loose != null) {
                return loose;
            }
        }
        Path packed = commonDir.resolve("packed-refs");
        if (!Files.isRegularFile(packed)) {
            return null;
        }
        for (String line : Files.readAllLines(packed, StandardCharsets.UTF_8)) {
            String[] fields = line.trim().split(" ");
            if (fields.length == 2
                    && fields[1].equals(ref)
                    && COMMIT.matcher(fields[0]).matches()) {
                return fields[0];
            }
        }
        return null;
    }

    private static String firstLine(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        return lines.isEmpty() ? null : lines.get(0).trim();
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            file.toFile().deleteOnExit();
        }
    }

    /** Returns the Maven module directory whose {@code target} holds the anchor class, or {@code null}. */
    public static Path moduleRoot(Class<?> anchor) {
        return moduleRootForCodeSource(codeSourcePath(anchor));
    }

    static Path moduleRootForCodeSource(Path location) {
        if (location == null || location.getFileName() == null) {
            return null;
        }
        Path normalized = location.toAbsolutePath().normalize();
        Path target;
        if (Files.isDirectory(normalized)
                && "classes".equals(normalized.getFileName().toString())) {
            target = normalized.getParent();
        } else if (Files.isRegularFile(normalized)
                && normalized.getFileName().toString().endsWith(".jar")) {
            target = normalized.getParent();
        } else {
            return null;
        }
        if (target == null
                || target.getFileName() == null
                || !"target".equals(target.getFileName().toString())) {
            return null;
        }
        Path root = target.getParent();
        return root != null && Files.isRegularFile(root.resolve("pom.xml")) ? root : null;
    }

    static String fingerprint(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Path is required");
        }
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to fingerprint " + path, exception);
        }
    }

    private static Path codeSourcePath(Class<?> anchor) {
        try {
            URL url = anchor.getProtectionDomain().getCodeSource().getLocation();
            return url == null
                    ? null
                    : java.nio.file.Paths.get(url.toURI()).toAbsolutePath().normalize();
        } catch (SecurityException | URISyntaxException | IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-256", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
