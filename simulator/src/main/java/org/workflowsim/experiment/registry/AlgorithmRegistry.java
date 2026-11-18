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

public final class AlgorithmRegistry {

    private AlgorithmRegistry() {}

    public static final class ValidationInput {
        private final ReplicaCatalog.FileSystem fileSystem;
        private final DataMovementModel dataMovementModel;
        private final boolean taskCostMatrixDeclared;
        private final FailureModelConfig failureModel;
        private final Parameters.PlanningAlgorithm planningAlgorithm;
        private final boolean platformPowerDeclared;
        private final boolean platformFrequencyTiersDeclared;

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

        public ValidationInput(
                ReplicaCatalog.FileSystem fileSystem,
                DataMovementModel dataMovementModel,
                boolean taskCostMatrixDeclared,
                FailureModelConfig failureModel,
                Parameters.PlanningAlgorithm planningAlgorithm) {
            this(fileSystem, dataMovementModel, taskCostMatrixDeclared, failureModel, planningAlgorithm, false, false);
        }

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

        public ReplicaCatalog.FileSystem getFileSystem() {
            return fileSystem;
        }

        public DataMovementModel getDataMovementModel() {
            return dataMovementModel;
        }

        public boolean isTaskCostMatrixDeclared() {
            return taskCostMatrixDeclared;
        }

        public FailureModelConfig getFailureModel() {
            return failureModel;
        }

        public Parameters.PlanningAlgorithm getPlanningAlgorithm() {
            return planningAlgorithm;
        }

        public boolean isPlatformPowerDeclared() {
            return platformPowerDeclared;
        }

        public boolean isPlatformFrequencyTiersDeclared() {
            return platformFrequencyTiersDeclared;
        }
    }

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

    public static boolean isRegistered(String label) {
        return label != null && providers().containsKey(normalize(label));
    }

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

        if (declaration.isPowerModelRequired() && !input.isPlatformPowerDeclared()) {
            throw new IllegalArgumentException("Algorithm '" + label + "' declares powerModelRequired, "
                    + "but the platform profile declares neither per-VM power fields "
                    + "(idlePowerWatts/maxPowerWatts on PlatformProfile.VmSpec) nor a frequencyTier "
                    + "table (BAT-D2 powered predicate: either face counts as powered)");
        }

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
