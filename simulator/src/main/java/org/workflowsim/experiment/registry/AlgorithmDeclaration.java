package org.workflowsim.experiment.registry;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.workflowsim.utils.ReplicaCatalog;

/**
 * Self-declared combination contract of a registered scheduling algorithm (2026-10-23,
 * BAT-3). The standard runner consumes exactly these fields to validate a configuration
 * the same way it validates its built-in matrix rows: file-system scope, accepted
 * data-movement model kinds, task-cost-matrix acceptance, and failure-model acceptance.
 *
 * <p>The declaration is the algorithm author's contract statement. The platform enforces
 * it fail-fast; it does not verify that the declared class actually implements a
 * literature-grade heuristic — that claim remains the author's responsibility.</p>
 *
 * <p>BAT-E3 (2026-10-27) energy-dimension declaration fields: {@code energyAware}
 * declares that the algorithm's scoring/selection logic uses energy cost;
 * {@code powerModelRequired} requires the platform profile to provide a per-VM power
 * field (otherwise registration rejects that configuration); {@code energyObjectiveWeight}
 * is the author-declared energy trade-off weight (positive and finite only; 0 means it
 * does not enter the objective). All three are off by default, and all are enforced
 * through {@link AlgorithmRegistry} in validate(), coupled with the platform power
 * field.</p>
 *
 * <p>BAT-C5 (2026-10-28) carbon-dimension declaration fields: {@code carbonAware} and
 * {@code carbonObjectiveWeight} mirror the energy triple along the same implication
 * lattice -- a positive weight implies carbonAware, and because both v1 carbon accounting
 * modes (intensity/budget) ride on the already-audited energy chain (design document
 * section 2.2), it also implies energyAware; budget-window scenarios are carried by the
 * carbon budget model (platform carbonWindows declaration) instead, with no separate
 * carbon platform gate field added. All off by default (0/undeclared carbon weight),
 * zero impact on existing registrations.</p>
 *
 * <p>BAT-D4 (2026-10-29) DVFS-dimension declaration fields
 * (docs/advanced/DVFS_DIMENSION_DESIGN.md section 2.6): {@code dvfsAware} declares that
 * the algorithm makes per-Job frequency decisions through the tier API; when paired with
 * {@code powerModelRequired} the injection factor warns that the tier table
 * <b>replaces</b> the flat power field (BAT-D1 single source of truth), so an algorithm
 * that requires a tier table must not also declare a flat power gate --
 * {@code frequencyTiersRequired} requires the platform profile to declare a tier table
 * for every VM (or every VM participating in the computation) (BAT-D4 implication
 * lattice: required implies aware). All off by default, zero impact on existing
 * registrations.</p>
 */
public final class AlgorithmDeclaration {

    /** File-system scope the declared algorithm is valid for. */
    public enum FileSystemScope {
        /** Usable with either supported catalog file system. */
        ANY,
        /** Requires LOCAL replica placement (e.g. locality-aware decisions). */
        LOCAL_ONLY,
        /** Requires SHARED datacenter-level storage semantics. */
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

    /** @return the file-system scope this algorithm declares support for */
    public FileSystemScope getFileSystemScope() {
        return fileSystemScope;
    }

    /** @return the data-movement {@link org.workflowsim.data.DataMovementModel.Kind} names
     *         the declaration covers; empty means every kind the runner itself supports */
    public Set<String> getAllowedDataMovementKinds() {
        return allowedDataMovementKinds;
    }

    /** @return whether configurations declaring a task-cost matrix may use this algorithm */
    public boolean acceptsTaskCostMatrix() {
        return acceptsTaskCostMatrix;
    }

    /** @return whether configs with an enabled failure model may use this algorithm */
    public boolean allowsFailureModel() {
        return allowsFailureModel;
    }

    /** @return a one-line human description surfaced in validation errors and docs */
    public String getDescription() {
        return description;
    }

    /** BAT-E3: @return whether the algorithm declares use of energy cost in its decision logic */
    public boolean isEnergyAware() {
        return energyAware;
    }

    /** BAT-E3: @return whether the algorithm requires the platform profile to declare a per-VM power field */
    public boolean isPowerModelRequired() {
        return powerModelRequired;
    }

    /**
     * BAT-E3: @return the declared energy objective weight (energy-makespan product
     *         trade-off coefficient; 0 means it does not enter the objective)
     */
    public double getEnergyObjectiveWeight() {
        return energyObjectiveWeight;
    }

    /** BAT-C5: @return whether the algorithm declares use of carbon cost in its decision
     *         logic (intensity or budget model) */
    public boolean isCarbonAware() {
        return carbonAware;
    }

    /** BAT-C5: @return the declared carbon objective weight (carbon-latency trade-off coefficient; 0=not part) */
    public double getCarbonObjectiveWeight() {
        return carbonObjectiveWeight;
    }

    /** BAT-D4: @return whether the algorithm declares per-Job frequency decisions through the tier API */
    public boolean isDvfsAware() {
        return dvfsAware;
    }

    /** BAT-D4: @return whether the algorithm requires the platform profile to declare a
     *         tier table per VM (implies dvfsAware) */
    public boolean isFrequencyTiersRequired() {
        return frequencyTiersRequired;
    }

    /** Returns whether the declared file-system scope covers the given configured system. */
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

    /** Builder with the same validation discipline as the rest of the configuration API. */
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

        /** Declares the file-system scope this algorithm is valid for (default ANY). */
        public Builder fileSystemScope(FileSystemScope scope) {
            if (scope == null) {
                throw new IllegalArgumentException("FileSystemScope is required");
            }
            this.fileSystemScope = scope;
            return this;
        }

        /** Adds one accepted data-movement kind name (e.g. LEGACY_WORKFLOWSIM_V1). */
        public Builder allowDataMovementKind(String kind) {
            if (kind == null || kind.trim().isEmpty()) {
                throw new IllegalArgumentException("data-movement kind must be a non-empty name");
            }
            allowedDataMovementKinds.add(kind);
            return this;
        }

        /** Declares whether a declared task-cost matrix may accompany this algorithm. */
        public Builder acceptsTaskCostMatrix(boolean accepts) {
            this.acceptsTaskCostMatrix = accepts;
            return this;
        }

        /** Declares whether an enabled failure model may accompany this algorithm. */
        public Builder allowsFailureModel(boolean allows) {
            this.allowsFailureModel = allows;
            return this;
        }

        /** BAT-E3: declares that the algorithm's decision logic uses energy cost (default false). */
        public Builder energyAware(boolean aware) {
            this.energyAware = aware;
            return this;
        }

        /** BAT-E3: requires the configured platform profile to provide a per-VM power field (default false). */
        public Builder powerModelRequired(boolean required) {
            this.powerModelRequired = required;
            if (required && !energyAware) {
                this.energyAware = true; // required implies aware
            }
            return this;
        }

        /** BAT-E3: declares the energy objective weight; must be >= 0 and finite (default 0, not part). */
        public Builder energyObjectiveWeight(double weight) {
            if (weight < 0.0 || !Double.isFinite(weight)) {
                throw new IllegalArgumentException("energyObjectiveWeight must be finite and >= 0: " + weight);
            }
            this.energyObjectiveWeight = weight;
            if (weight > 0.0) {
                this.energyAware = true; // positive weight implies aware
            }
            return this;
        }

        /** BAT-C5: declares that the algorithm's decision logic uses carbon cost (intensity
         *         or budget model; default false). */
        public Builder carbonAware(boolean aware) {
            this.carbonAware = aware;
            return this;
        }

        /**
         * BAT-C5: declares the carbon objective weight; must be >= 0 and finite (default 0,
         * not part). A positive weight implies carbonAware, and because both v1 carbon
         * accounting modes are defined on the already conserved-locked energy chain
         * (docs/advanced/CARBON_DIMENSION_DESIGN.md section 2.2), it also implies
         * energyAware -- the implication lattice is isomorphic to the energy triple, with
         * zero special-casing at the registration entry point.
         */
        public Builder carbonObjectiveWeight(double weight) {
            if (weight < 0.0 || !Double.isFinite(weight)) {
                throw new IllegalArgumentException("carbonObjectiveWeight must be finite and >= 0: " + weight);
            }
            this.carbonObjectiveWeight = weight;
            if (weight > 0.0) {
                this.carbonAware = true; // positive weight implies aware
                this.energyAware = true; // v1 carbon rides the conserved energy chain
            }
            return this;
        }

        /** BAT-D4: declares per-Job frequency decisions through the tier API (default false;
         *         descriptive flag, not a gate). */
        public Builder dvfsAware(boolean aware) {
            this.dvfsAware = aware;
            return this;
        }

        /**
         * BAT-D4: requires the configured platform profile to declare a tier table per VM
         * (default false). required implies aware, the same lattice as
         * {@code powerModelRequired implies energyAware}: the required flag raises the aware
         * flag in the setter, with zero special-casing at the registration entry point.
         */
        public Builder frequencyTiersRequired(boolean required) {
            this.frequencyTiersRequired = required;
            if (required) {
                this.dvfsAware = true; // required implies aware
            }
            return this;
        }

        /** Sets the one-line description used in errors and documentation. */
        public Builder description(String text) {
            if (text == null) {
                throw new IllegalArgumentException("description must not be null (use empty string)");
            }
            this.description = text;
            return this;
        }

        /** Builds the immutable declaration; all validation happens here, fail-fast. */
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
