package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Structural guard tests for the SimulationMetrics three-surface contract (AUD-F14).
 *
 * <p>SimulationMetrics is a deliberately monolithic class ("move-only, no-restructure"
 * maintenance mode): one new metric must stay in sync across the accumulator logic, the
 * field/assembly surface, and the accessor surface, and a missed accessor or field does not
 * fail compilation. These tests lock the structural symmetry so the miss turns red:</p>
 *
 * <ul>
 *   <li>every Gson-serialized field (the artifact output surface) must have a same-named
 *       public no-arg accessor, and every accessor must map back to a serialized field or be
 *       listed as an explicit compatibility alias;</li>
 *   <li>the Gson output key surface must equal the non-transient field surface exactly;</li>
 *   <li>the validator's required-metrics list must stay inside the real serialization
 *       surface.</li>
 * </ul>
 *
 * <p>Accumulator-logic misses (a metric computed wrongly or not computed at all) cannot be
 * caught structurally; they stay covered by the behavioral tests in SimulationMetricsTest
 * and the recomputation checks of AUD-F05.</p>
 */
class SimulationMetricsContractTest {

    /**
     * Accessors without a same-named field: deliberate compatibility aliases over an existing
     * serialized field, kept for callers using the historical names. Each entry documents the
     * field it actually reads.
     */
    private static final Set<String> ALIASED_ACCESSORS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            // getSimulationEndSeconds() -> makespanSeconds (same simulated-seconds value)
            "simulationEndSeconds",
            // getCompletedJobAttemptCount() -> jobOutcomeCount
            "completedJobAttemptCount",
            // getCompletedComputeAttemptCount() -> computeJobOutcomeCount
            "completedComputeAttemptCount",
            // isWorkflowCompletedSuccessfully() -> allLogicalTasksCompletedSuccessfully
            "workflowCompletedSuccessfully",
            // getModeledRequiredInputDemandFileCount() -> modeledDataTransferFileCount
            "modeledRequiredInputDemandFileCount",
            // getTotalModeledRequiredInputDemandBytes() -> totalModeledRequiredInputBytes
            "totalModeledRequiredInputDemandBytes")));

    /** Fields intentionally present in the serialized output without a same-named accessor. */
    private static final Set<String> FIELDS_WITHOUT_ACCESSOR = Collections.unmodifiableSet(new HashSet<String>());

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    @Test
    void everyNonTransientFieldHasASameNamedAccessorAndViceVersa() throws Exception {
        Set<String> fields = serializedFieldNames();
        Set<String> accessors = accessorNames();

        // Transient-backed accessors are legitimate asymmetry: the field is deliberately
        // excluded from the artifact surface, and its accessor serves Java-side callers.
        // Computed dynamically so a newly added transient field needs no list edit.
        Set<String> transientBackedAccessors = new TreeSet<String>();
        for (Field field : SimulationMetrics.class.getDeclaredFields()) {
            if (Modifier.isTransient(field.getModifiers())) {
                transientBackedAccessors.add(field.getName());
            }
        }

        Set<String> accessorsWithoutField = new TreeSet<String>(accessors);
        accessorsWithoutField.removeAll(fields);
        accessorsWithoutField.removeAll(transientBackedAccessors);
        accessorsWithoutField.removeAll(ALIASED_ACCESSORS);
        assertTrue(
                accessorsWithoutField.isEmpty(),
                "Accessors without a same-named serialized field and without an alias entry"
                        + " (add the field, or extend ALIASED_ACCESSORS with a justification): "
                        + accessorsWithoutField);

        Set<String> fieldsWithoutAccessor = new TreeSet<String>(fields);
        fieldsWithoutAccessor.removeAll(accessors);
        fieldsWithoutAccessor.removeAll(FIELDS_WITHOUT_ACCESSOR);
        assertTrue(
                fieldsWithoutAccessor.isEmpty(),
                "Serialized fields without a same-named accessor (add the accessor, or extend"
                        + " FIELDS_WITHOUT_ACCESSOR with a justification): " + fieldsWithoutAccessor);
    }

    @Test
    void gsonOutputKeysMatchTheNonTransientFieldsExactly() {
        JsonObject metrics =
                GSON.toJsonTree(GSON.fromJson("{}", SimulationMetrics.class)).getAsJsonObject();
        Set<String> outputKeys = new TreeSet<String>();
        for (String key : metrics.keySet()) {
            outputKeys.add(key);
        }
        assertEquals(
                serializedFieldNames(),
                outputKeys,
                "The metrics artifact key surface must equal the non-transient field surface");
        assertFalse(outputKeys.contains("perTierBusySecondsByVm"));
        assertFalse(outputKeys.contains("fleetPerTierBusySeconds"));
    }

    @Test
    void validatorRequiredMetricFieldsExistInTheSerializationSurface() throws Exception {
        Field required = ExperimentArtifactValidator.class.getDeclaredField("REQUIRED_METRIC_FIELDS");
        required.setAccessible(true);
        String[] names = (String[]) required.get(null);
        assertTrue(names.length > 0, "the validator required-metrics list must not be empty");

        JsonObject metrics =
                GSON.toJsonTree(GSON.fromJson("{}", SimulationMetrics.class)).getAsJsonObject();
        for (String name : names) {
            assertTrue(
                    metrics.has(name),
                    "REQUIRED_METRIC_FIELDS entry \"" + name + "\" is not a real serialized metrics field (AUD-F14)");
        }
    }

    /** @return names of non-static non-transient instance fields (the serialized surface) */
    private static Set<String> serializedFieldNames() {
        Set<String> names = new TreeSet<String>();
        for (Field field : SimulationMetrics.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers)) {
                names.add(field.getName());
            }
        }
        return names;
    }

    /** @return property names implied by public no-arg get/is accessors declared on the class */
    private static Set<String> accessorNames() {
        Set<String> names = new TreeSet<String>();
        for (Method method : SimulationMetrics.class.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (!Modifier.isPublic(modifiers) || method.getParameterCount() != 0 || Modifier.isStatic(modifiers)) {
                continue;
            }
            String name = method.getName();
            if (name.startsWith("get") && name.length() > "get".length()) {
                names.add(propertyOf(name, "get".length()));
            } else if (name.startsWith("is") && name.length() > "is".length()) {
                names.add(propertyOf(name, "is".length()));
            }
        }
        return names;
    }

    /** @return the property name of a {@code getX}/{@code isX} accessor */
    private static String propertyOf(String methodName, int prefixLength) {
        char first = Character.toLowerCase(methodName.charAt(prefixLength));
        return first + methodName.substring(prefixLength + 1);
    }
}
