package org.workflowsim.experiments.standard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class StandardStudySmokeTest {

    @TempDir
    Path directory;

    @Test
    void twoInstancesTwoAlgorithmsOneCellEndToEnd() throws Exception {
        List<Path> instances = Arrays.asList(montage(), tinyChain());
        StandardStudy.Design design = StandardStudy.Design.builder(
                        StandardStudy.Layer.STATIC, StandardStudy.EvidenceClass.WFGEN_SYNTHETIC)
                .candidates(Arrays.asList("SHARED_STORAGE_HEFT", "SHARED_STORAGE_PEFT"))
                .instances(instances)
                .vmCounts(Collections.singletonList(8))
                .ccrTargets(Collections.singletonList(1.0))
                .build();
        StandardStudy.Result result = StandardStudy.run(design, directory.resolve("out"));

        Map<String, Object> header = result.getHeader();
        assertEquals("standard-study-v1", header.get("protocolId"));
        assertEquals("WORK_CONSERVING_TASK_EXECUTION_V3", header.get("executionSemantics"));
        assertEquals("SHARED_STORAGE_HEFT", header.get("baseline"));
        assertNotNull(header.get("gitCommit"));
        assertEquals(64, ((String) header.get("corpusIndexSha256")).length());

        assertEquals(4, result.getRows().size());
        Set<String> cells = new HashSet<String>();
        for (Map<String, Object> row : result.getRows()) {
            assertTrue(cells.add((String) row.get("cellId")));
            assertEquals("v8-c0", row.get("factorCell"));
            assertEquals(1.0, (Double) row.get("ccrAchieved"), 1.0e-12, "closed-form CCR reaches the target");
            double makespan = (Double) row.get("makespanSeconds");
            assertEquals(
                    (Double) row.get("serialOnFastestSeconds") / makespan,
                    (Double) row.get("speedupVsSerialOnFastest"),
                    1.0e-12);
            assertNotNull(row.get("slrIoInclusive"), "controlled scope: SHARED, no failure, no overhead");
            assertTrue((Double) row.get("slrIoInclusive") >= 1.0 - 1.0e-9, "SLR denominator is a lower bound");
            assertEquals(1.0, (Double) row.get("boundedSlowdownTauSeconds"), 0.0);
            assertTrue((Double) row.get("energyJoules") > 0.0);
            assertTrue((Double) row.get("costUnits") > 0.0);
        }

        assertEquals(
                StandardStudy.TESTED_METRICS.size(), result.getComparisons().size());
        for (Map<String, Object> comparison : result.getComparisons()) {
            assertEquals("SHARED_STORAGE_PEFT", comparison.get("candidate"));
            assertEquals(2, comparison.get("pairedInstances"));
            assertEquals(1, comparison.get("holmFamilySize"));
            String status = (String) comparison.get("wilcoxonStatus");

            assertFalse((Boolean) comparison.get("significantAfterHolm"), status);
        }

        JsonObject document = JsonParser.parseString(
                        new String(Files.readAllBytes(result.getJson()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(
                "standard-study-v1",
                document.getAsJsonObject("protocol").get("protocolId").getAsString());
        assertEquals(4, document.getAsJsonArray("rows").size());
        List<String> csv = Files.readAllLines(result.getRowsCsv(), StandardCharsets.UTF_8);
        assertEquals("# protocolId=standard-study-v1", csv.get(0));
        assertEquals(6 + 1 + 4, csv.size(), "6 header lines, 1 column line, 4 rows");
        assertTrue(Files.isRegularFile(directory.resolve("out/campaign/experiment-campaign-index.json")));
    }

    @Test
    void designRejectsCrossLayerCandidatesStaticFailuresAndMixedEvidence() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> StandardStudy.Design.builder(
                        StandardStudy.Layer.STATIC, StandardStudy.EvidenceClass.WFGEN_SYNTHETIC)
                .candidates(Arrays.asList("SHARED_STORAGE_HEFT", "READY_BATCH_MCT"))
                .instances(Collections.singletonList(montage()))
                .build());
        assertThrows(IllegalArgumentException.class, () -> StandardStudy.Design.builder(
                        StandardStudy.Layer.ONLINE, StandardStudy.EvidenceClass.WFGEN_SYNTHETIC)
                .candidates(Arrays.asList("READY_BATCH_MCT"))
                .instances(Collections.singletonList(montage()))
                .build());
        assertThrows(IllegalArgumentException.class, () -> StandardStudy.Design.builder(
                        StandardStudy.Layer.STATIC, StandardStudy.EvidenceClass.WFGEN_SYNTHETIC)
                .instances(Collections.singletonList(montage()))
                .failures(Collections.singletonList(10.0), 3)
                .build());
        StandardStudy.Design mixed = StandardStudy.Design.builder(
                        StandardStudy.Layer.STATIC, StandardStudy.EvidenceClass.WFINSTANCES_REAL_DERIVED)
                .instances(Collections.singletonList(montage()))
                .vmCounts(Collections.singletonList(8))
                .ccrTargets(Collections.singletonList(1.0))
                .build();
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> StandardStudy.run(mixed, directory.resolve("mixed")));
        assertTrue(failure.getMessage().contains("must not be mixed"), failure.getMessage());
    }

    @Test
    void serialOnFastestMatchesTheEnvelopeRule() {
        org.workflowsim.Task a = new org.workflowsim.Task(1, 1000L);
        org.workflowsim.FileItem input = new org.workflowsim.FileItem("x", 2_000_000.0);
        input.setType(org.workflowsim.utils.Parameters.FileType.INPUT);
        a.addFile(input);
        org.workflowsim.Task b = new org.workflowsim.Task(2, 3000L);
        assertEquals(2.5, StandardStudy.serialOnFastestSeconds(Arrays.asList(a, b), 4.0, 2000.0), 1.0e-12);
    }

    private static Path montage() throws Exception {
        String root = System.getProperty("workflowsim.datasetRoot");
        Path base = root == null ? Paths.get("..", "datasets") : Paths.get(root);
        Path file = base.resolve("wfformat/montage/n100/montage-100-000.json")
                .toAbsolutePath()
                .normalize();
        assertTrue(Files.isRegularFile(file), "standard checkout retains " + file);
        return file;
    }

    private Path tinyChain() throws Exception {
        Path dir = Files.createDirectories(directory.resolve("wfformat-fixture"));
        List<String> tasks = new ArrayList<String>();
        tasks.add(task("t1", "[]", "[\"t2\"]", "[\"x\"]", "[\"a\"]"));
        tasks.add(task("t2", "[\"t1\"]", "[\"t3\"]", "[\"a\"]", "[\"b\"]"));
        tasks.add(task("t3", "[\"t2\"]", "[]", "[\"b\"]", "[\"c\"]"));
        String json = "{\"name\":\"chain\",\"schemaVersion\":\"1.5\",\"workflow\":{\"specification\":{\"tasks\":["
                + String.join(",", tasks)
                + "],\"files\":[{\"id\":\"x\",\"sizeInBytes\":20000000},{\"id\":\"a\",\"sizeInBytes\":10000000},"
                + "{\"id\":\"b\",\"sizeInBytes\":30000000},{\"id\":\"c\",\"sizeInBytes\":1000}]},"
                + "\"execution\":{\"makespanInSeconds\":60,\"executedAt\":\"2026-11-25T00:00:00Z\",\"tasks\":["
                + "{\"id\":\"t1\",\"runtimeInSeconds\":10},{\"id\":\"t2\",\"runtimeInSeconds\":20},"
                + "{\"id\":\"t3\",\"runtimeInSeconds\":30}]}}}";
        Path file = dir.resolve("chain-3-000.json");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String task(String id, String parents, String children, String inputs, String outputs) {
        return "{\"name\":\"" + id + "\",\"id\":\"" + id + "\",\"parents\":" + parents + ",\"children\":" + children
                + ",\"inputFiles\":" + inputs + ",\"outputFiles\":" + outputs + "}";
    }
}
