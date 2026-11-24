package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;

class SessionResetMatrixTest {

    private static final List<String> AUDIT_CLASSES = Arrays.asList(
            "org.workflowsim.utils.Parameters",
            "org.cloudbus.cloudsim.core.CloudSim",
            "org.workflowsim.failure.FailureParameters",
            "org.workflowsim.failure.FailureMonitor",
            "org.cloudbus.cloudsim.NetworkTopology",
            "org.workflowsim.utils.ReplicaCatalog",
            "org.cloudbus.cloudsim.Log",
            "org.workflowsim.utils.SimulationRandom",
            "org.workflowsim.utils.DistributionGenerator",
            "org.workflowsim.utils.SimulationSession",
            "org.cloudbus.cloudsim.Consts");

    private static final List<String> VIRGIN_FENCE = Arrays.asList(
            "org.cloudbus.cloudsim.NetworkTopology.*",
            "org.cloudbus.cloudsim.Consts.MILLION",
            "org.workflowsim.utils.Parameters.SOURCE");

    @TempDir
    Path tempDir;

    @Test
    void consecutiveRunsLeaveStableStateAndDormantGlobalsVirgin() throws Exception {
        boolean logWasDisabled = Log.isDisabled();
        Log.disable();
        try {
            Path workflow = minimalChainDax();
            Map<String, String> virgin = snapshot();
            SimulationReport first = runOnce(workflow, SchedulingAlgorithm.FCFS);
            Map<String, String> afterFirst = snapshot();
            SimulationReport second = runOnce(workflow, SchedulingAlgorithm.READY_BATCH_MCT);
            Map<String, String> afterSecond = snapshot();

            assertTrue(
                    first.getMetrics().isAllLogicalTasksCompletedSuccessfully(),
                    "the first round (FCFS) run must complete successfully, otherwise the state audit"
                            + " is meaningless");
            assertTrue(
                    second.getMetrics().isAllLogicalTasksCompletedSuccessfully(),
                    "the second round (MCT) run must complete successfully, otherwise the state audit"
                            + " is meaningless");

            List<String> unstable = diff("afterFirst", afterFirst, "afterSecond", afterSecond);
            assertTrue(
                    unstable.isEmpty(),
                    "mutable static state drifted after two consecutive standard runs (cross-run leak):\n"
                            + String.join("\n", unstable));

            List<String> fenceBroken = diffViolations(virgin, afterSecond);
            assertTrue(
                    fenceBroken.isEmpty(),
                    "the standard runner touched a dormant global outside its declared scope (F-002/F-003"
                            + " class trap):\n"
                            + String.join("\n", fenceBroken));
        } finally {
            Log.setDisabled(logWasDisabled);
        }
    }

    private SimulationReport runOnce(Path workflow, SchedulingAlgorithm algorithm)
            throws org.workflowsim.exception.SimulationExecutionException {
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .schedulingAlgorithm(algorithm)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(org.workflowsim.data.DataMovementModel.legacyWorkflowsimV1())
                .costModel(Parameters.CostModel.DATACENTER)
                .randomSeed(42L)
                .build();
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("reset-matrix", 2);
        return new SimulationRunner().run(config, platform);
    }

    private Path minimalChainDax() throws Exception {
        StringBuilder dax = new StringBuilder();
        dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        dax.append("<adag version=\"3.3\" count=\"4\">\n");
        for (int id = 1; id <= 4; id++) {
            dax.append("  <job id=\"t").append(id).append("\" name=\"chain\" runtime=\"100\"/>\n");
        }
        for (int id = 2; id <= 4; id++) {
            dax.append("  <child ref=\"t").append(id).append("\">\n");
            dax.append("    <parent ref=\"t").append(id - 1).append("\"/>\n");
            dax.append("  </child>\n");
        }
        dax.append("</adag>\n");
        Path path = tempDir.resolve("minimal-chain.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static Map<String, String> snapshot() throws Exception {
        Map<String, String> state = new TreeMap<String, String>();
        for (String className : AUDIT_CLASSES) {
            Class<?> type = Class.forName(className);
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                    continue;
                }

                if (field.isSynthetic() || field.getName().startsWith("$")) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(null);
                state.put(className + "." + field.getName(), canonical(value, 0));
            }
        }
        return state;
    }

    private static String canonical(Object value, int depth) {
        if (value == null) {
            return "null";
        }
        if (depth > 3) {
            return shortIdentity(value);
        }
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum) {
            return value.getClass().getSimpleName() + ":" + String.valueOf(value);
        }
        if (value.getClass().isArray()) {
            Object[] array = (Object[]) wrapArray(value);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(canonical(array[i], depth + 1));
            }
            return sb.append("]").toString();
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            StringBuilder sb = new StringBuilder("{");
            for (Object key : new TreeMap<Object, Object>((Map<Object, Object>) map).keySet()) {
                sb.append(canonical(key, depth + 1))
                        .append("=")
                        .append(canonical(map.get(key), depth + 1))
                        .append(",");
            }
            return sb.append("}").toString();
        }
        if (value instanceof Collection) {
            Collection<?> collection = (Collection<?>) value;
            StringBuilder sb = new StringBuilder("[");
            for (Object item : collection) {
                sb.append(canonical(item, depth + 1)).append(",");
            }
            return sb.append("]").toString();
        }
        return shortIdentity(value);
    }

    private static Object wrapArray(Object array) {
        if (array instanceof Object[]) {
            return array;
        }
        int length = java.lang.reflect.Array.getLength(array);
        Object[] boxed = new Object[length];
        for (int i = 0; i < length; i++) {
            boxed[i] = java.lang.reflect.Array.get(array, i);
        }
        return boxed;
    }

    private static String shortIdentity(Object value) {
        return value.getClass().getName() + "@" + System.identityHashCode(value);
    }

    private static List<String> diff(
            String leftLabel, Map<String, String> left, String rightLabel, Map<String, String> right) {
        List<String> diffs = new ArrayList<String>();
        for (String key : new TreeMap<String, String>(right).keySet()) {
            String before = left.get(key);
            String after = right.get(key);
            if (before == null || !before.equals(after)) {
                diffs.add(String.format("  %s: [%s]=%s → [%s]=%s", key, leftLabel, before, rightLabel, after));
            }
        }
        return diffs;
    }

    private static List<String> diffViolations(Map<String, String> virgin, Map<String, String> current) {
        List<String> violations = new ArrayList<String>();
        for (String rule : VIRGIN_FENCE) {
            boolean prefixRule = rule.endsWith(".*");
            String target = prefixRule ? rule.substring(0, rule.length() - 2) : rule;
            for (String key : current.keySet()) {
                boolean matches = prefixRule ? key.startsWith(target + ".") : key.equals(target);
                if (!matches) {
                    continue;
                }
                if (!virgin.get(key).equals(current.get(key))) {
                    violations.add(String.format("  %s: %s → %s", key, virgin.get(key), current.get(key)));
                }
            }
        }
        return violations;
    }
}
