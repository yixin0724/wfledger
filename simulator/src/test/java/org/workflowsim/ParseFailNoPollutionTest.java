package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * C12: a parser rejection must not leave half-committed state (review-engineering Phase 3
 * pollution probe).
 *
 * <p>Hard-rule basis (transactional conservation law): a failed parse is an input that <b>did not
 * happen</b> -- any write it makes to {@link ReplicaCatalog} is pollution: when another workflow is
 * parsed in a retry within the same JVM, the "first-declared size" rule would <b>silently replace</b>
 * the newly declared size with the stale size of the polluted entry, biasing the transferred-byte
 * model with no alert at all.</p>
 *
 * <p>The existing DOCTYPE/XXE/structural rejection behavior is already covered by
 * WorkflowParserStrictValidationTest; this class targets <b>global-state cleanliness at the failure
 * instant</b>: after every illegal sample is rejected, the catalog must contain no file registered
 * by that parse.</p>
 */
class ParseFailNoPollutionTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void cleanSlate() {
        ReplicaCatalog.reset();
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
        Parameters.reset();
    }

    /** Same driver recipe as the existing strict-validation tests: write file -> Parameters.init -> parse(). */
    private void parseFile(Path input) throws Exception {
        Parameters.init(
                2,
                input.toString(),
                null,
                null,
                null,
                null,
                Parameters.SchedulingAlgorithm.FCFS,
                Parameters.PlanningAlgorithm.INVALID,
                null,
                0L);
        new WorkflowParser(0).parse();
    }

    private Path write(String body) throws Exception {
        Path path = tempDir.resolve("probe-" + System.nanoTime() + ".dax");
        Files.write(path, body.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    /**
     * C12-1: when the second job is invalid (negative runtime), inputs already parsed for the first
     * job must not linger in the catalog.
     */
    @Test
    void invalidSecondJobLeavesNoHalfCommittedInputs() throws Exception {
        Path dax = write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"2\">\n"
                + "  <job id=\"good-first\" name=\"a\" runtime=\"100\">\n"
                + "    <uses name=\"early-input.bin\" link=\"input\" size=\"300000\"/>\n"
                + "    <uses name=\"early-output.bin\" link=\"output\" size=\"100\"/>\n"
                + "  </job>\n"
                + "  <job id=\"bad-second\" name=\"b\" runtime=\"-5\"/>\n"
                + "</adag>\n");
        assertThrows(WorkflowValidationException.class, () -> parseFile(dax));
        assertTrue(
                !ReplicaCatalog.containsFile("early-input.bin"),
                "the catalog after rejection must not retain" + " an input the first job registered (half commit)");
        assertTrue(
                !ReplicaCatalog.containsFile("early-output.bin"),
                "output file names must not be registered"
                        + " (outputs go through clause registration, not file registration)");
    }

    /**
     * C12-2: the missing-dependency case (a child references a nonexistent parent) fails only after
     * jobs were registered.
     */
    @Test
    void missingDependencyReferenceLeavesNoInputs() throws Exception {
        Path dax = write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"2\">\n"
                + "  <job id=\"j1\" name=\"a\" runtime=\"10\">\n"
                + "    <uses name=\"f1.bin\" link=\"input\" size=\"50\"/>\n"
                + "  </job>\n"
                + "  <job id=\"j2\" name=\"b\" runtime=\"10\"/>\n"
                + "  <child ref=\"j2\"><parent ref=\"nonexistent\"/></child>\n"
                + "</adag>\n");
        assertThrows(WorkflowValidationException.class, () -> parseFile(dax));
        assertTrue(!ReplicaCatalog.containsFile("f1.bin"), "catalog must stay clean after dependency failure");
    }

    /**
     * C12-3: the observable harm chain of pollution -- a stale size registered by a failed parse
     * must not affect a subsequent successful parse. (The successful sample declares a different size
     * for the same file name than the failed sample does; after the successful parse the newly
     * declared size must be the one registered.)
     */
    @Test
    void failedParseMustNotSwapSizesForSubsequentSuccessfulParse() throws Exception {
        Path bad = write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"2\">\n"
                + "  <job id=\"a\" name=\"a\" runtime=\"10\">\n"
                + "    <uses name=\"same-name.bin\" link=\"input\" size=\"300000\"/>\n"
                + "  </job>\n"
                + "  <job id=\"b\" name=\"b\" runtime=\"-1\"/>\n"
                + "</adag>\n");
        Path good = write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"1\">\n"
                + "  <job id=\"a\" name=\"a\" runtime=\"10\">\n"
                + "    <uses name=\"same-name.bin\" link=\"input\" size=\"700000\"/>\n"
                + "  </job>\n"
                + "  <job id=\"b\" name=\"b\" runtime=\"10\"/>\n"
                + "  <child ref=\"b\"><parent ref=\"a\"/></child>\n"
                + "</adag>\n");
        assertThrows(WorkflowValidationException.class, () -> parseFile(bad));
        parseFile(good);
        org.workflowsim.FileItem item = ReplicaCatalog.getFile("same-name.bin");
        assertTrue(item != null, "the file must exist after the successful retry parse");
        double wrongSize = Double.parseDouble("300000");
        double expectedSize = Double.parseDouble("700000");
        assertTrue(
                item.getSize() == expectedSize && item.getSize() != wrongSize,
                "pollution harm chain: a stale size from a failed parse must not replace the new declaration of a"
                        + " successful parse (current=" + item.getSize() + ")");
    }

    /**
     * C12-4: a duplicate job id fails at the second duplicate -- inputs of the first job must not
     * linger either.
     */
    @Test
    void duplicateJobIdLeavesNoInputs() throws Exception {
        Path dax = write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"2\">\n"
                + "  <job id=\"dup\" name=\"a\" runtime=\"10\">\n"
                + "    <uses name=\"dup-input.bin\" link=\"input\" size=\"10\"/>\n"
                + "  </job>\n"
                + "  <job id=\"dup\" name=\"b\" runtime=\"10\"/>\n"
                + "</adag>\n");
        assertThrows(WorkflowValidationException.class, () -> parseFile(dax));
        assertTrue(!ReplicaCatalog.containsFile("dup-input.bin"), "catalog stays clean after a duplicate-ID rejection");
    }

    /** C12-5: when the DAG has a cycle (illegal graph), the catalog is clean after rejection. */
    @Test
    void cyclicDagRejectionLeavesNoInputs() throws Exception {
        Path dax = write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"2\">\n"
                + "  <job id=\"x\" name=\"a\" runtime=\"10\">\n"
                + "    <uses name=\"cycle-input.bin\" link=\"input\" size=\"10\"/>\n"
                + "  </job>\n"
                + "  <job id=\"y\" name=\"b\" runtime=\"10\"/>\n"
                + "  <child ref=\"x\"><parent ref=\"y\"/></child>\n"
                + "  <child ref=\"y\"><parent ref=\"x\"/></child>\n"
                + "</adag>\n");
        assertThrows(WorkflowValidationException.class, () -> parseFile(dax));
        assertTrue(!ReplicaCatalog.containsFile("cycle-input.bin"), "catalog stays clean after a cycle rejection");
    }

    /** Same scope for the WfCommons JSON path: the catalog is clean after an illegal instance is rejected. */
    @Test
    void invalidJsonInstanceLeavesNoInputs() throws Exception {
        Path json = tempDir.resolve("bad-instance.json");
        Files.write(
                json,
                ("{\n"
                                + "  \"schemaVersion\": \"1.5\",\n"
                                + "  \"workflow\": { \"specification\": { \"tasks\": [\n"
                                + "      { \"id\": \"t1\", \"name\": \"n\", \"type\": \"compute\",\n"
                                + "        \"inputFiles\": [\"f.json\"], \"outputFiles\": [] },\n"
                                + "      { \"id\": \"t2\", \"name\": \"n\", \"type\": \"compute\",\n"
                                + "        \"inputFiles\": [], \"outputFiles\": [] }\n"
                                + "    ],\n"
                                + "    \"files\": [ { \"id\": \"f.json\", \"sizeInBytes\": 100 } ],\n"
                                + "    \"machine\": {} },\n"
                                + "  \"execution\": { \"tasks\": [ { \"id\": \"t1\", \"runtimeInSeconds\": 1 } ] }\n"
                                + "}\n")
                        .getBytes(StandardCharsets.UTF_8));
        // t2 has no runtime -> rejected.
        assertThrows(Exception.class, () -> new WfCommonsJsonParser().parse(json.toString(), 0, 1));
        assertTrue(!ReplicaCatalog.containsFile("f.json"), "the catalog must be clean after a JSON rejection");
    }

    /**
     * Robustness baseline of the success path: a legal parse still registers (so the fix does not
     * become "never register").
     */
    @Test
    void successfulParseStillRegistersInputs() throws Exception {
        Path dax = write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"2\">\n"
                + "  <job id=\"a\" name=\"a\" runtime=\"10\">\n"
                + "    <uses name=\"ok-input.bin\" link=\"input\" size=\"123\"/>\n"
                + "  </job>\n"
                + "  <job id=\"b\" name=\"b\" runtime=\"10\"/>\n"
                + "  <child ref=\"b\"><parent ref=\"a\"/></child>\n"
                + "</adag>\n");
        parseFile(dax);
        org.workflowsim.FileItem item = ReplicaCatalog.getFile("ok-input.bin");
        assertTrue(
                item != null,
                "a successful parse must register the input file (baseline: the fix" + " must not overcorrect)");
    }
}
