package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Failsafe coverage for summary fallback and escaping branches. */
class ExperimentSummaryWriterIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void summaryHandlesMissingValuesAndEscapesHtmlAndCsv() throws Exception {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("workflow", null);
        row.put("algorithm", null);
        row.put("candidate", "candidate\"<id>");
        row.put("status", "FAILED");
        row.put("error", "bad,\"value\"\nnext");
        ExperimentSummaryWriter.write("A <study>", Collections.singletonList(row), directory);
        String csv = new String(Files.readAllBytes(directory.resolve("summary.csv")), StandardCharsets.UTF_8);
        String html = new String(Files.readAllBytes(directory.resolve("summary.html")), StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"bad,\"\"value\"\"\nnext\""));
        assertTrue(html.contains("A &lt;study&gt;"));
        assertTrue(html.contains("candidate&quot;&lt;id&gt;"));
    }
}
