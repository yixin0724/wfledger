package org.workflowsim.experiments.workloads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.workflowsim.Task;
import org.workflowsim.WfCommonsJsonParser;
import org.workflowsim.WorkflowInputReport;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class WfgenStandardCorpusParseTest {

    private static final String FAMILY = "wfgen-standard";

    private static final int EXPECTED_RETAINED = 200;

    @BeforeEach
    void quiet() {
        Log.disable();
    }

    @AfterEach
    void restore() {
        Log.enable();
    }

    static Stream<Arguments> retainedInstances() throws Exception {
        List<Arguments> arguments = new ArrayList<Arguments>();
        for (JsonElement element : index().getAsJsonArray("instances")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("retained_in_standard_checkout").getAsBoolean()) {
                arguments.add(Arguments.of(entry.get("wfformat_json").getAsString(), entry));
            }
        }
        return arguments.stream();
    }

    @Test
    void standardCheckoutRetainsEverySmallInstance() throws Exception {
        assertEquals(EXPECTED_RETAINED, retainedInstances().count());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retainedInstances")
    void parsesWithIndexedStructure(String relative, JsonObject entry) throws Exception {
        Path input = datasetRoot().resolve(FAMILY).resolve(relative);
        assertTrue(Files.isRegularFile(input), "retained instance must be present: " + input);
        assertEquals(entry.get("sha256").getAsString(), sha256(input), "indexed bytes of " + relative);
        JsonObject stats = entry.getAsJsonObject("stats");
        try (SimulationSession session = SimulationSession.open(
                SimulationConfig.builder(input.toString(), 1).build())) {
            WfCommonsJsonParser.ParseResult result = new WfCommonsJsonParser().parse(input.toString(), 0, 0);
            List<Task> tasks = result.getTasks();
            assertEquals(entry.get("num_tasks").getAsInt(), tasks.size(), "task count of " + relative);
            assertEquals(stats.get("tasks").getAsInt(), result.getInputReport().getTaskCount());
            assertEquals(
                    WorkflowInputReport.Format.WFCOMMONS_JSON,
                    result.getInputReport().getFormat());
            assertEquals("1.6", result.getInputReport().getDeclaredVersion());
            int edges = 0;
            Map<Integer, Integer> widths = new TreeMap<Integer, Integer>();
            for (Task task : tasks) {
                edges += task.getChildList().size();
                widths.merge(task.getDepth(), 1, Integer::sum);
            }
            int depth = ((TreeMap<Integer, Integer>) widths).lastKey();
            int widest = 0;
            for (int width : widths.values()) {
                widest = Math.max(widest, width);
            }
            assertEquals(stats.get("edges").getAsInt(), edges, "edge count of " + relative);
            assertEquals(stats.get("depth").getAsInt(), depth, "depth of " + relative);
            assertEquals(stats.get("max_level_width").getAsInt(), widest, "widest level of " + relative);
        }
    }

    private static JsonObject index() throws Exception {
        Path path = datasetRoot().resolve(FAMILY).resolve("INDEX.json");
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static Path datasetRoot() {
        return Paths.get(System.getProperty("workflowsim.datasetRoot", "../datasets"))
                .toAbsolutePath()
                .normalize();
    }
}
