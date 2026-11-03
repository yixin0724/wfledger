package org.workflowsim.examples;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test support that runs the teaching CLI in a separate JVM.
 *
 * <p>The legacy WorkflowSim entry points depend on CloudSim and on several WorkflowSim static
 * states. A test must not call these {@code main} methods sequentially inside one JVM, because
 * the state left by one entry point can mask an initialization defect of another. This helper
 * reuses the runtime classpath strategy of the examples aggregator but keeps every child-process
 * output in the JUnit temporary directory, so that a failure can supply a diagnosable log tail.</p>
 */
final class CliProcessTestSupport {

    static final long DEFAULT_TIMEOUT_SECONDS = 90L;
    private static final int DIAGNOSTIC_OUTPUT_LIMIT = 12_000;

    private CliProcessTestSupport() {}

    /**
     * Starts a main class from the project root and merges stdout/stderr into a temporary log.
     *
     * @param workspaceRoot project root that contains {@code datasets/}
     * @param logDirectory temporary log directory managed by JUnit
     * @param mainClass fully qualified name of the main class to execute
     * @param arguments command-line arguments passed to the main class
     * @return exit, timeout and output information of the child process
     * @throws IOException when the child process or the log file cannot be created
     */
    static ProcessResult runMain(Path workspaceRoot, Path logDirectory, String mainClass, String... arguments)
            throws IOException {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            throw new IllegalArgumentException("Workspace root must be an existing directory: " + workspaceRoot);
        }
        if (logDirectory == null) {
            throw new IllegalArgumentException("Log directory cannot be null");
        }
        if (mainClass == null || mainClass.trim().isEmpty()) {
            throw new IllegalArgumentException("Main class cannot be empty");
        }
        Files.createDirectories(logDirectory);
        Path output = Files.createTempFile(logDirectory, "workflowsim-cli-", ".log");

        List<String> command = new ArrayList<String>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(WorkflowSimAllExamplesTester.runtimeClasspath());
        command.add(mainClass);
        if (arguments != null) {
            command.addAll(Arrays.asList(arguments));
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspaceRoot.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        Process process = builder.start();

        boolean finished;
        try {
            finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + mainClass, exception);
        }

        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(10L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping timed out process " + mainClass, exception);
            }
        }

        String contents = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        return new ProcessResult(mainClass, output, finished, finished ? process.exitValue() : -1, contents);
    }

    private static String javaExecutable() {
        return new File(System.getProperty("java.home"), "bin/java").getPath();
    }

    /** Immutable test result of a terminated child process. */
    static final class ProcessResult {
        private final String mainClass;
        private final Path outputPath;
        private final boolean finished;
        private final int exitCode;
        private final String output;

        ProcessResult(String mainClass, Path outputPath, boolean finished, int exitCode, String output) {
            this.mainClass = mainClass;
            this.outputPath = outputPath;
            this.finished = finished;
            this.exitCode = exitCode;
            this.output = output;
        }

        boolean isTimedOut() {
            return !finished;
        }

        int getExitCode() {
            return exitCode;
        }

        String getOutput() {
            return output;
        }

        /** Returns assertion diagnostics that carry the log path and the output tail. */
        String diagnostic() {
            String tail = output;
            if (tail.length() > DIAGNOSTIC_OUTPUT_LIMIT) {
                tail = "[output truncated; final " + DIAGNOSTIC_OUTPUT_LIMIT + " characters follow]\n"
                        + tail.substring(tail.length() - DIAGNOSTIC_OUTPUT_LIMIT);
            }
            return "mainClass=" + mainClass + " exitCode=" + exitCode + " timedOut=" + isTimedOut() + " log="
                    + outputPath + "\n" + tail;
        }
    }
}
