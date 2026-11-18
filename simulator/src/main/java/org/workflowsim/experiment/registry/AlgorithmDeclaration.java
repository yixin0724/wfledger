package org.workflowsim.experiment.registry;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.workflowsim.utils.ReplicaCatalog;

public final class AlgorithmDeclaration {

    public enum FileSystemScope {
        ANY,

        LOCAL_ONLY,

        SHARED_ONLY
    }

    private final FileSystemScope fileSystemScope;
    private final Set<String> allowedDataMovementKinds;
    private final boolean acceptsTaskCostMatrix;
    private final boolean allowsFailureModel;
    private final String description;
    private final boolean energyAware;
    private final boolean powerModelRequired;
    private final double energyObjectiveWeight;
    private final boolean carbonAware;
    private final double carbonObjectiveWeight;
    private final boolean dvfsAware;
    private final boolean frequencyTiersRequired;

    private AlgorithmDeclaration(
            FileSystemScope fileSystemScope,
            Set<String> allowedDataMovementKinds,
            boolean acceptsTaskCostMatrix,
            boolean allowsFailureModel,
            String description,
            boolean energyAware,
            boolean powerModelRequired,
            double energyObjectiveWeight,
            boolean carbonAware,
            double carbonObjectiveWeight,
            boolean dvfsAware,
            boolean frequencyTiersRequired) {
        this.fileSystemScope = fileSystemScope;
        this.allowedDataMovementKinds = Collections.unmodifiableSet(new LinkedHashSet<>(allowedDataMovementKinds));
        this.acceptsTaskCostMatrix = acceptsTaskCostMatrix;
        this.allowsFailureModel = allowsFailureModel;
        this.description = description;
        this.energyAware = energyAware;
        this.powerModelRequired = powerModelRequired;
        this.energyObjectiveWeight = energyObjectiveWeight;
        this.carbonAware = carbonAware;
        this.carbonObjectiveWeight = carbonObjectiveWeight;
        this.dvfsAware = dvfsAware;
        this.frequencyTiersRequired = frequencyTiersRequired;
    }

    public FileSystemScope getFileSystemScope() {
        return fileSystemScope;
    }

    public Set<String> getAllowedDataMovementKinds() {
        return allowedDataMovementKinds;
    }

    public boolean acceptsTaskCostMatrix() {
        return acceptsTaskCostMatrix;
    }

    public boolean allowsFailureModel() {
        return allowsFailureModel;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnergyAware() {
        return energyAware;
    }

    public boolean isPowerModelRequired() {
        return powerModelRequired;
    }

    public double getEnergyObjectiveWeight() {
        return energyObjectiveWeight;
    }

    public boolean isCarbonAware() {
        return carbonAware;
    }

    public double getCarbonObjectiveWeight() {
        return carbonObjectiveWeight;
    }

    public boolean isDvfsAware() {
        return dvfsAware;
    }

    public boolean isFrequencyTiersRequired() {
        return frequencyTiersRequired;
    }

    public boolean coversFileSystem(ReplicaCatalog.FileSystem fileSystem) {
        if (fileSystem == null) {
            return false;
        }
        switch (fileSystemScope) {
            case ANY:
                return true;
            case LOCAL_ONLY:
                return fileSystem == ReplicaCatalog.FileSystem.LOCAL;
            case SHARED_ONLY:
                return fileSystem == ReplicaCatalog.FileSystem.SHARED;
            default:
                return false;
        }
    }

    public static final class Builder {
        private FileSystemScope fileSystemScope = FileSystemScope.ANY;
        private final Set<String> allowedDataMovementKinds = new LinkedHashSet<>();
        private boolean acceptsTaskCostMatrix = false;
        private boolean allowsFailureModel = false;
        private String description = "";
        private boolean energyAware = false;
        private boolean powerModelRequired = false;
        private double energyObjectiveWeight = 0.0;
        private boolean carbonAware = false;
        private double carbonObjectiveWeight = 0.0;
        private boolean dvfsAware = false;
        private boolean frequencyTiersRequired = false;

        public Builder fileSystemScope(FileSystemScope scope) {
            if (scope == null) {
                throw new IllegalArgumentException("FileSystemScope is required");
            }
            this.fileSystemScope = scope;
            return this;
        }

        public Builder allowDataMovementKind(String kind) {
            if (kind == null || kind.trim().isEmpty()) {
                throw new IllegalArgumentException("data-movement kind must be a non-empty name");
            }
            allowedDataMovementKinds.add(kind);
            return this;
        }

        public Builder acceptsTaskCostMatrix(boolean accepts) {
            this.acceptsTaskCostMatrix = accepts;
            return this;
        }

        public Builder allowsFailureModel(boolean allows) {
            this.allowsFailureModel = allows;
            return this;
        }

        public Builder energyAware(boolean aware) {
            this.energyAware = aware;
            return this;
        }

        public Builder powerModelRequired(boolean required) {
            this.powerModelRequired = required;
            if (required && !energyAware) {
                this.energyAware = true;
            }
            return this;
        }

        public Builder energyObjectiveWeight(double weight) {
            if (weight < 0.0 || !Double.isFinite(weight)) {
                throw new IllegalArgumentException("energyObjectiveWeight must be finite and >= 0: " + weight);
            }
            this.energyObjectiveWeight = weight;
            if (weight > 0.0) {
                this.energyAware = true;
            }
            return this;
        }

        public Builder carbonAware(boolean aware) {
            this.carbonAware = aware;
            return this;
        }

        public Builder carbonObjectiveWeight(double weight) {
            if (weight < 0.0 || !Double.isFinite(weight)) {
                throw new IllegalArgumentException("carbonObjectiveWeight must be finite and >= 0: " + weight);
            }
            this.carbonObjectiveWeight = weight;
            if (weight > 0.0) {
                this.carbonAware = true;
                this.energyAware = true;
            }
            return this;
        }

        public Builder dvfsAware(boolean aware) {
            this.dvfsAware = aware;
            return this;
        }

        public Builder frequencyTiersRequired(boolean required) {
            this.frequencyTiersRequired = required;
            if (required) {
                this.dvfsAware = true;
            }
            return this;
        }

        public Builder description(String text) {
            if (text == null) {
                throw new IllegalArgumentException("description must not be null (use empty string)");
            }
            this.description = text;
            return this;
        }

        public AlgorithmDeclaration build() {
            return new AlgorithmDeclaration(
                    fileSystemScope,
                    allowedDataMovementKinds,
                    acceptsTaskCostMatrix,
                    allowsFailureModel,
                    description,
                    energyAware,
                    powerModelRequired,
                    energyObjectiveWeight,
                    carbonAware,
                    carbonObjectiveWeight,
                    dvfsAware,
                    frequencyTiersRequired);
        }
    }
}
