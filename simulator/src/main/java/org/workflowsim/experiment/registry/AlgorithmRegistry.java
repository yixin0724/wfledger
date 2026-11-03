package org.workflowsim.experiment.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

import org.workflowsim.data.DataMovementModel;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * Stateless lookup and combination gate for {@link SchedulingAlgorithmProvider} entries
 * discovered through the standard Java {@link ServiceLoader} SPI (2026-10-23, BAT-3).
 *
 * <p>The registry holds no mutable global state: every lookup scans the current
 * classpath's providers deterministically (ServiceLoader order), which keeps it safe
 * under the single-JVM serialization discipline without a new session-managed static.
 * Duplicate labels are rejected loudly rather than shadowed.</p>
 *
 * <p>{@link #validate(String, ValidationInput)} is the fail-fast consumption point: the
 * same combination matrix the standard runner applies to built-in labels — file system,
 * data-movement kind, task-cost matrix, failure model, and the planning-layer pairing —
 * is checked against the provider's self-declared {@link AlgorithmDeclaration}.</p>
 */
public final class AlgorithmRegistry {

    private AlgorithmRegistry() {}

    /** The configuration facts a registered label is validated against. */
    public static final class ValidationInput {
        private final ReplicaCatalog.FileSystem fileSystem;
        private final DataMovementModel dataMovementModel;
        private final boolean taskCostMatrixDeclared;
        private final FailureModelConfig failureModel;
        private final Parameters.PlanningAlgorithm planningAlgorithm;
        private final boolean platformPowerDeclared;
        private final boolean platformFrequencyTiersDeclared;

        /** Assembles the validation input from the standard run configuration. */
        public ValidationInput(SimulationConfig config) {
            this(
                    config.getFileSystem(),
                    config.getDataMovementModel(),
                    config.getTaskCostMatrix() != null
                            && !config.getTaskCostMatrix().asMap().isEmpty(),
                    config.getFailureModel(),
                    config.getPlanningAlgorithm(),
                    false,
                    false);
        }

        /** Legacy 5-arg constructor (call surface before BAT-E3); undeclared power/tiers mean false. */
        public ValidationInput(
                ReplicaCatalog.FileSystem fileSystem,
                DataMovementModel dataMovementModel,
                boolean taskCostMatrixDeclared,
                FailureModelConfig failureModel,
                Parameters.PlanningAlgorithm planningAlgorithm) {
            this(fileSystem, dataMovementModel, taskCostMatrixDeclared, failureModel, planningAlgorithm, false, false);
        }

        /**
         * BAT-E3 6-arg (power-inclusive) legacy delegation surface: the frequency-tier fact
         * defaults to false (BAT-D4 stays compatible).
         */
        public ValidationInput(
                ReplicaCatalog.FileSystem fileSystem,
                DataMovementModel dataMovementModel,
                boolean taskCostMatrixDeclared,
                FailureModelConfig failureModel,
                Parameters.PlanningAlgorithm planningAlgorithm,
                boolean platformPowerDeclared) {
            this(
                    fileSystem,
                    dataMovementModel,
                    taskCostMatrixDeclared,
                    failureModel,
                    planningAlgorithm,
                    platformPowerDeclared,
                    false);
        }

        /**
         * Constructs the validation input explicitly (fixed-field, immutable).
         * BAT-D4 (2026-10-29) adds the frequency-tier fact parameter
         * (docs/advanced/DVFS_DIMENSION_DESIGN.md section 2.6).
         */
        public ValidationInput(
                ReplicaCatalog.FileSystem fileSystem,
                DataMovementModel dataMovementModel,
                boolean taskCostMatrixDeclared,
                FailureModelConfig failureModel,
                Parameters.PlanningAlgorithm planningAlgorithm,
                boolean platformPowerDeclared,
                boolean platformFrequencyTiersDeclared) {
            this.fileSystem = fileSystem;
            this.dataMovementModel = dataMovementModel;
            this.taskCostMatrixDeclared = taskCostMatrixDeclared;
            this.failureModel = failureModel;
            this.planningAlgorithm = planningAlgorithm;
            this.platformPowerDeclared = platformPowerDeclared;
            this.platformFrequencyTiersDeclared = platformFrequencyTiersDeclared;
        }

        /** @return the configured file system */
        public ReplicaCatalog.FileSystem getFileSystem() {
            return fileSystem;
        }

        /** @return the configured data movement model */
        public DataMovementModel getDataMovementModel() {
            return dataMovementModel;
        }

        /** @return whether the configuration carries a non-empty task-cost matrix */
        public boolean isTaskCostMatrixDeclared() {
            return taskCostMatrixDeclared;
        }

        /** @return the configured failure model (never null; NONE default) */
        public FailureModelConfig getFailureModel() {
            return failureModel;
        }

        /** @return the configured planning algorithm (INVALID for pure online tracks) */
        public Parameters.PlanningAlgorithm getPlanningAlgorithm() {
            return planningAlgorithm;
        }

        /**
         * BAT-E3: @return whether the platform profile declares any VM power face. BAT-D6
         * (2026-10-29) rule: true when either the flat power fields (idle/max) or any
         * frequency-tier table is declared -- the same source as the energy chain's BAT-D2
         * powered predicate (the frequency-tier table replaces the flat fields; the two are
         * mutually exclusive and either one yields a real energy ledger).
         */
        public boolean isPlatformPowerDeclared() {
            return platformPowerDeclared;
        }

        /** BAT-D4: @return whether the platform profile declares any VM frequency-tier table */
        public boolean isPlatformFrequencyTiersDeclared() {
            return platformFrequencyTiersDeclared;
        }
    }

    /**
     * Returns all providers visible on the classpath, keyed by label.
     *
     * @throws IllegalStateException on duplicate labels or labels colliding with built-in
     *         enum names — assignment ambiguity must be explicit
     */
    public static Map<String, SchedulingAlgorithmProvider> providers() {
        Map<String, SchedulingAlgorithmProvider> byLabel = new LinkedHashMap<String, SchedulingAlgorithmProvider>();
        ServiceLoader<SchedulingAlgorithmProvider> loader = ServiceLoader.load(SchedulingAlgorithmProvider.class);
        for (SchedulingAlgorithmProvider provider : loader) {
            String label = normalize(provider.label());
            if (label == null) {
                throw new IllegalStateException("Registered scheduling provider has an empty label: "
                        + provider.getClass().getName());
            }
            for (Parameters.SchedulingAlgorithm builtin : Parameters.SchedulingAlgorithm.values()) {
                if (label.equals(builtin.name())) {
                    throw new IllegalStateException(
                            "Registered label '" + label + "' collides with the built-in scheduling enum");
                }
            }
            for (Parameters.PlanningAlgorithm builtin : Parameters.PlanningAlgorithm.values()) {
                if (label.equals(builtin.name())) {
                    throw new IllegalStateException(
                            "Registered label '" + label + "' collides with the built-in planning enum");
                }
            }
            if (byLabel.put(label, provider) != null) {
                throw new IllegalStateException("Duplicate registered scheduling label: '" + label + "'");
            }
        }
        return byLabel;
    }

    /** @return true when a provider with this exact label is registered */
    public static boolean isRegistered(String label) {
        return label != null && providers().containsKey(normalize(label));
    }

    /**
     * Returns the provider for a label.
     *
     * @throws IllegalArgumentException when the label is unregistered
     */
    public static SchedulingAlgorithmProvider find(String label) {
        Map<String, SchedulingAlgorithmProvider> providers = providers();
        SchedulingAlgorithmProvider provider = providers.get(normalize(label));
        if (provider == null) {
            List<String> known = new ArrayList<String>(providers.keySet());
            throw new IllegalArgumentException("No registered scheduling algorithm labeled '" + label
                    + "' on the classpath" + (known.isEmpty() ? " (no providers registered)" : "; known: " + known));
        }
        return provider;
    }

    /**
     * Fail-fast combination gate for a registered label: validates the provider's
     * self-declared metadata against the run configuration.
     *
     * @throws IllegalArgumentException with the precise violated contract clause
     */
    public static void validate(String label, ValidationInput input) {
        SchedulingAlgorithmProvider provider = find(label);
        AlgorithmDeclaration declaration = provider.declaration();
        if (declaration == null) {
            throw new IllegalArgumentException(
                    "Registered scheduling algorithm '" + label + "' declares no contract metadata");
        }
        if (input.getPlanningAlgorithm() != Parameters.PlanningAlgorithm.INVALID) {
            throw new IllegalArgumentException("Registered scheduling label '" + label
                    + "' belongs to the plain online scheduling track and requires PlanningAlgorithm.INVALID, got "
                    + input.getPlanningAlgorithm() + " — a registered label cannot be paired with a planner");
        }
        if (!declaration.coversFileSystem(input.getFileSystem())) {
            throw new IllegalArgumentException("Registered scheduling algorithm '" + label + "' declares "
                    + declaration.getFileSystemScope() + " and does not cover file system " + input.getFileSystem());
        }
        String kind = input.getDataMovementModel() == null
                ? null
                : input.getDataMovementModel().getKind().name();
        if (kind != null
                && !declaration.getAllowedDataMovementKinds().isEmpty()
                && !declaration.getAllowedDataMovementKinds().contains(kind)) {
            throw new IllegalArgumentException("Registered scheduling algorithm '" + label
                    + "' declares data-movement kinds " + declaration.getAllowedDataMovementKinds()
                    + ", which do not cover the configured " + kind);
        }
        // BAT-E3 (2026-10-27): an algorithm declaring powerModelRequired is rejected on a
        // platform profile without power. BAT-D6 (2026-10-29): the power fact follows the
        // same rule as the energy chain (BAT-D2 powered predicate: flat power fields OR any
        // declared frequency-tier table means powered -- under the single-source structure
        // where the frequency-tier table replaces the flat fields, a profile declaring
        // frequency tiers necessarily yields a real energy ledger); the rejection message
        // cites both declaration faces.
        if (declaration.isPowerModelRequired() && !input.isPlatformPowerDeclared()) {
            throw new IllegalArgumentException("Algorithm '" + label + "' declares powerModelRequired, "
                    + "but the platform profile declares neither per-VM power fields "
                    + "(idlePowerWatts/maxPowerWatts on PlatformProfile.VmSpec) nor a frequencyTier "
                    + "table (BAT-D2 powered predicate: either face counts as powered)");
        }
        // BAT-D4 (2026-10-29, docs/advanced/DVFS_DIMENSION_DESIGN.md section 2.6): an
        // algorithm declaring frequencyTiersRequired is rejected on a platform profile
        // without frequency tiers -- no tier can be assigned, so registration would mislead.
        if (declaration.isFrequencyTiersRequired() && !input.isPlatformFrequencyTiersDeclared()) {
            throw new IllegalArgumentException("Algorithm '" + label + "' declares frequencyTiersRequired, "
                    + "but the platform profile does not declare any VM frequencyTiers "
                    + "(PlatformProfile.VmSpec.frequencyTiers, BAT-D4)");
        }
        if (input.isTaskCostMatrixDeclared() && !declaration.acceptsTaskCostMatrix()) {
            throw new IllegalArgumentException("Registered scheduling algorithm '" + label
                    + "' declares that it does not accept a task-cost matrix");
        }
        if (input.getFailureModel() != null
                && input.getFailureModel().isEnabled()
                && !declaration.allowsFailureModel()) {
            throw new IllegalArgumentException("Registered scheduling algorithm '" + label
                    + "' declares that it does not accept an enabled failure model");
        }
    }

    private static String normalize(String label) {
        if (label == null) {
            return null;
        }
        String trimmed = label.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
