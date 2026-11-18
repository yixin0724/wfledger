package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

class WorkflowParserStrictValidationTest {

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void initializeParserState() {
        Parameters.reset();
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.SHARED);
    }

    @AfterEach
    void clearParserState() {
        Parameters.reset();
        ReplicaCatalog.reset();
    }

    @Test
    void rejectsDuplicateJobIdsAndNonFiniteRuntimes() throws Exception {
        assertThrows(
                WorkflowValidationException.class,
                () -> parse(dax("<job id=\"a\" runtime=\"1\"/><job id=\"a\" runtime=\"1\"/>")));
        assertThrows(WorkflowValidationException.class, () -> parse(dax("<job id=\"a\" runtime=\"NaN\"/>")));
    }

    @Test
    void rejectsUnsupportedFileLinksButToleratesConflictingInputSizes() throws Exception {

        assertThrows(
                WorkflowValidationException.class,
                () -> parse(dax(
                        "<job id=\"a\" runtime=\"1\"><uses name=\"x\" link=\"temporary\" size=\"1\"/>" + "</job>")));

        parse(dax("<job id=\"a\" runtime=\"1\"><uses name=\"x\" link=\"input\" size=\"1\"/>"
                + "</job><job id=\"b\" runtime=\"1\"><uses name=\"x\" link=\"input\" size=\"2\"/>"
                + "</job>"));
    }

    @Test
    void rejectsExternalDtdInsteadOfConsumingUnfingerprintedRuntime() throws Exception {
        Path dtd = temporaryDirectory.resolve("runtime.dtd");
        Files.write(dtd, "<!ENTITY runtime \"5\">".getBytes(StandardCharsets.UTF_8));
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE adag SYSTEM \"" + dtd.toUri() + "\">"
                + "<adag version=\"3.3\"><job id=\"a\" runtime=\"&runtime;\"/></adag>";
        assertThrows(
                WorkflowValidationException.class,
                () -> parse(xml),
                "The primary input hash must not omit a DTD that controls task execution");
    }

    @Test
    void rejectsDoctypeEvenWhenTheEntityIsDeclaredInline() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE adag [<!ENTITY runtime \"1\">]>"
                + "<adag version=\"3.3\"><job id=\"a\" runtime=\"&runtime;\"/></adag>";
        assertThrows(WorkflowValidationException.class, () -> parse(xml));
    }

    private void parse(String contents) throws Exception {
        Path input = temporaryDirectory.resolve("workflow.dax");
        Files.write(input, contents.getBytes(StandardCharsets.UTF_8));
        Parameters.init(
                1,
                input.toString(),
                null,
                null,
                new OverheadParameters(0, null, null, null, null, 0.0),
                new ClusteringParameters(0, 0, ClusteringParameters.ClusteringMethod.NONE, null),
                Parameters.SchedulingAlgorithm.FCFS,
                Parameters.PlanningAlgorithm.INVALID,
                null,
                0L);
        new WorkflowParser(0).parse();
    }

    private static String dax(String body) {
        return "<?xml version=\"1.0\"?><adag version=\"3.3\" name=\"strict\">" + body + "</adag>";
    }
}
