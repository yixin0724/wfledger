package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentProvenanceTest {

    private static final String COMMIT_A = "0123456789abcdef0123456789abcdef01234567";
    private static final String COMMIT_B = "89abcdef0123456789abcdef0123456789abcdef";

    @Test
    void resolvesModuleRootForClassesAndReactorJar(@TempDir Path temporaryDirectory) throws Exception {
        Path module = Files.createDirectory(temporaryDirectory.resolve("simulator"));
        Files.write(module.resolve("pom.xml"), "<project/>".getBytes(StandardCharsets.UTF_8));
        Path target = Files.createDirectory(module.resolve("target"));
        Path classes = Files.createDirectory(target.resolve("classes"));
        Path jar = Files.createFile(target.resolve("workflowsim-1.0.jar"));

        assertEquals(module.toAbsolutePath().normalize(), ExperimentProvenance.moduleRootForCodeSource(classes));
        assertEquals(module.toAbsolutePath().normalize(), ExperimentProvenance.moduleRootForCodeSource(jar));
    }

    @Test
    void doesNotInferAProjectRootForNonMavenCodeSources(@TempDir Path temporaryDirectory) throws Exception {
        Path standaloneJar = Files.createFile(temporaryDirectory.resolve("workflowsim-1.0.jar"));
        Path otherDirectory = Files.createDirectory(temporaryDirectory.resolve("classes"));

        assertNull(ExperimentProvenance.moduleRootForCodeSource(standaloneJar));
        assertNull(ExperimentProvenance.moduleRootForCodeSource(otherDirectory));
    }

    @Test
    void resolvesASymbolicHeadThroughALooseBranchRef(@TempDir Path root) throws Exception {
        Path git = Files.createDirectories(root.resolve(".git"));
        write(git.resolve("HEAD"), "ref: refs/heads/main\n");
        write(git.resolve("refs/heads/main"), COMMIT_A + "\n");
        Path module = Files.createDirectories(root.resolve("simulator/src"));

        assertEquals(COMMIT_A, ExperimentProvenance.gitCommit(module));
        assertEquals(COMMIT_A, ExperimentProvenance.gitCommit(root));
    }

    @Test
    void resolvesADetachedHead(@TempDir Path root) throws Exception {
        Path git = Files.createDirectories(root.resolve(".git"));
        write(git.resolve("HEAD"), COMMIT_B + "\n");

        assertEquals(COMMIT_B, ExperimentProvenance.gitCommit(root));
    }

    @Test
    void resolvesABranchStoredOnlyInPackedRefs(@TempDir Path root) throws Exception {
        Path git = Files.createDirectories(root.resolve(".git"));
        write(git.resolve("HEAD"), "ref: refs/heads/release\n");
        write(
                git.resolve("packed-refs"),
                "# pack-refs with: peeled fully-peeled sorted\n"
                        + COMMIT_A + " refs/heads/main\n"
                        + COMMIT_B + " refs/heads/release\n"
                        + "^" + COMMIT_A + "\n");

        assertEquals(COMMIT_B, ExperimentProvenance.gitCommit(root));
    }

    @Test
    void resolvesALinkedWorktreeThroughGitdirAndCommondir(@TempDir Path root) throws Exception {
        Path common = Files.createDirectories(root.resolve("main/.git"));
        Path worktreeGit = Files.createDirectories(common.resolve("worktrees/feature"));
        write(worktreeGit.resolve("HEAD"), "ref: refs/heads/feature\n");
        write(worktreeGit.resolve("commondir"), "../..\n");
        write(common.resolve("packed-refs"), COMMIT_A + " refs/heads/feature\n");
        Path worktree = Files.createDirectories(root.resolve("feature-checkout"));
        write(worktree.resolve(".git"), "gitdir: " + worktreeGit.toAbsolutePath() + "\n");

        assertEquals(COMMIT_A, ExperimentProvenance.gitCommit(worktree));

        write(common.resolve("refs/heads/feature"), COMMIT_B + "\n");
        assertEquals(COMMIT_B, ExperimentProvenance.gitCommit(worktree), "loose refs take precedence over packed");
    }

    @Test
    void recordsNullOutsideACheckoutOrForAnUnresolvableHead(@TempDir Path root) throws Exception {
        Path plain = Files.createDirectories(root.resolve("plain"));
        Path gitless = Files.createDirectories(plain.resolve("module"));
        Assumptions.assumeFalse(hasGitAncestor(gitless), "the temporary directory lies inside a Git checkout");
        assertNull(ExperimentProvenance.gitCommit(gitless));
        assertNull(ExperimentProvenance.gitDirty(gitless));
        assertNull(ExperimentProvenance.gitCommit(null));
        assertNull(ExperimentProvenance.gitDirty(null));

        Path unborn = Files.createDirectories(root.resolve("unborn"));
        Path git = Files.createDirectories(unborn.resolve(".git"));
        write(git.resolve("HEAD"), "ref: refs/heads/main\n");
        assertNull(ExperimentProvenance.gitCommit(unborn));

        Path malformed = Files.createDirectories(root.resolve("malformed"));
        write(malformed.resolve(".git"), "not a gitdir pointer\n");
        assertNull(ExperimentProvenance.gitCommit(malformed));
    }

    @Test
    void capturedExecutionRecordsOnlyTheGitIdentityAndWorkingDirectory() {
        Map<String, Object> provenance = ExperimentProvenance.capture();
        assertEquals(ExperimentProvenance.SCHEMA_V4, provenance.get("schema"));
        @SuppressWarnings("unchecked")
        Map<String, Object> execution = (Map<String, Object>) provenance.get("execution");
        assertEquals(
                java.util.Arrays.asList("workingDirectory", "gitCommit", "gitDirty"),
                new java.util.ArrayList<String>(execution.keySet()));
        Object commit = execution.get("gitCommit");
        assertTrue(commit == null || ((String) commit).matches("[0-9a-f]{40}"), String.valueOf(commit));
        if (commit == null) {
            assertNull(execution.get("gitDirty"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> core = (Map<String, Object>) provenance.get("core");
        assertEquals(
                java.util.Arrays.asList("groupId", "artifactId", "version", "anchorClass"),
                new java.util.ArrayList<String>(core.keySet()));
        assertFalse(core.containsKey("sourceTreeSha256"));
    }

    private static boolean hasGitAncestor(Path start) {
        for (Path directory = start.toAbsolutePath(); directory != null; directory = directory.getParent()) {
            if (Files.exists(directory.resolve(".git"))) {
                return true;
            }
        }
        return false;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
