package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.SimulationConfig;

/**
 * Immutable, executable declaration of a research experiment campaign.
 *
 * <p>Each comparison group must declare exactly one baseline. All candidate variants
 * within a group must share the same randomization interpretation, yet each cell still
 * stores its own explicit seed sequence so that the experiment protocol can be audited
 * independently.</p>
 *
 * <p>Each comparison group must also stay within one mapping-cost caliber: the online
 * ready-batch caliber (bare length/MIPS ECT) and the static planning caliber
 * (TaskExecutionModel matrix seconds) must not be placed side by side in one group,
 * because a same-named algorithm carries two different cost semantics across the two
 * calibers (AUD-F09).</p>
 */
public final class ExperimentPlan {

    public static final String SCHEMA = "workflowsim-experiment-plan-v1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");

    private final String id;
    private final List<Cell> cells;

    private ExperimentPlan(Builder builder) {
        this.id = builder.id;
        this.cells = Collections.unmodifiableList(new ArrayList<Cell>(builder.cells));
    }

    /**
     * Creates an experiment plan builder.
     *
     * @param id stable identifier of the plan; must match the identifier format defined
     *           by this class
     * @return a builder that does not yet contain any cell
     * @throws IllegalArgumentException when the identifier is invalid
     */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String getId() {
        return id;
    }

    public List<Cell> getCells() {
        return cells;
    }

    /**
     * Returns the total number of repeated runs declared by all cells of the plan.
     *
     * @return the sum of the replication counts of every cell
     */
    public int getRunCount() {
        int result = 0;
        for (Cell cell : cells) {
            result += cell.getSeedPlan().getReplicationCount();
        }
        return result;
    }

    /**
     * Builder for an immutable ExperimentPlan: identified by a validated plan ID, appending cells
     * (configuration + seed plan pairs) in registration order; see the enclosing class contract.
     */
    public static final class Builder {
        private final String id;
        private final List<Cell> cells = new ArrayList<Cell>();

        private Builder(String id) {
            this.id = requireIdentifier(id, "Experiment plan ID");
        }

        /**
         * Appends one candidate, platform, and workflow combination to the plan.
         *
         * <p>Identifier, baseline, and randomization-design constraints across cells are
         * validated together in {@link #build()}.</p>
         *
         * @param value non-null experiment cell to declare
         * @return this builder, to support chained calls
         * @throws IllegalArgumentException when the cell is {@code null}
         */
        public Builder addCell(Cell value) {
            cells.add(requireNonNull(value, "Experiment cell"));
            return this;
        }

        /**
         * Validates and freezes the current declaration.
         *
         * @return an immutable plan satisfying the comparison-group, baseline, candidate
         *         identifier, randomization-design, and cost-caliber constraints
         * @throws IllegalArgumentException when no cell is declared, or when any
         *         cross-cell constraint does not hold
         */
        public ExperimentPlan build() {
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("Experiment plan requires at least one cell");
            }
            Set<String> cellIds = new HashSet<String>();
            Set<String> candidates = new HashSet<String>();
            Map<String, Cell> baselineByGroup = new HashMap<String, Cell>();
            Map<String, RandomizationDesign> designByGroup = new HashMap<String, RandomizationDesign>();
            Map<String, AlgorithmCatalog.CostComparisonCaliber> caliberByGroup =
                    new HashMap<String, AlgorithmCatalog.CostComparisonCaliber>();
            Map<String, Cell> caliberOwnerByGroup = new HashMap<String, Cell>();
            for (Cell cell : cells) {
                if (!cellIds.add(cell.getId())) {
                    throw new IllegalArgumentException("Experiment plan has duplicate cell ID " + cell.getId());
                }
                if (!candidates.add(cell.getComparisonGroup() + "\u0000" + cell.getCandidateId())) {
                    throw new IllegalArgumentException("Comparison group " + cell.getComparisonGroup()
                            + " has duplicate candidate ID " + cell.getCandidateId());
                }
                RandomizationDesign existing = designByGroup.put(
                        cell.getComparisonGroup(), cell.getSeedPlan().getRandomizationDesign());
                if (existing != null && existing != cell.getSeedPlan().getRandomizationDesign()) {
                    throw new IllegalArgumentException("Comparison group " + cell.getComparisonGroup()
                            + " has inconsistent randomization designs");
                }
                if (cell.isBaseline() && baselineByGroup.put(cell.getComparisonGroup(), cell) != null) {
                    throw new IllegalArgumentException(
                            "Comparison group " + cell.getComparisonGroup() + " has more than one baseline");
                }
                // AUD-F09: one comparison group must not place the two mapping-cost calibers
                // side by side; a same-named algorithm carries two different cost semantics
                // across them, so the mixed comparison is rejected at construction instead of
                // being executed. Cells outside both calibers stay unconstrained.
                AlgorithmCatalog.CostComparisonCaliber caliber = AlgorithmCatalog.costComparisonCaliber(
                        cell.getConfig().getPlanningAlgorithm(),
                        cell.getConfig().getSchedulingAlgorithm());
                if (caliber != AlgorithmCatalog.CostComparisonCaliber.NONE) {
                    AlgorithmCatalog.CostComparisonCaliber existingCaliber =
                            caliberByGroup.get(cell.getComparisonGroup());
                    if (existingCaliber != null && existingCaliber != caliber) {
                        Cell owner = caliberOwnerByGroup.get(cell.getComparisonGroup());
                        throw new IllegalArgumentException("Comparison group " + cell.getComparisonGroup()
                                + " mixes the " + describeCostCaliber(existingCaliber, owner)
                                + " cost caliber with the " + describeCostCaliber(caliber, cell)
                                + " cost caliber: a same-named algorithm carries two different "
                                + "mapping-cost semantics across the two calibers, so keep one "
                                + "comparison group within one cost caliber (AUD-F09).");
                    }
                    if (existingCaliber == null) {
                        caliberByGroup.put(cell.getComparisonGroup(), caliber);
                        caliberOwnerByGroup.put(cell.getComparisonGroup(), cell);
                    }
                }
            }
            for (Cell cell : cells) {
                if (!baselineByGroup.containsKey(cell.getComparisonGroup())) {
                    throw new IllegalArgumentException(
                            "Comparison group " + cell.getComparisonGroup() + " has no baseline cell");
                }
            }
            return new ExperimentPlan(this);
        }
    }

    /**
     * Immutable experiment cell formed by a candidate algorithm, a platform, a workflow
     * configuration, and its explicit seed sequence.
     */
    public static final class Cell {
        private final String id;
        private final String comparisonGroup;
        private final String candidateId;
        private final boolean baseline;
        private final SimulationConfig config;
        private final PlatformProfile platform;
        private final SeedPlan seedPlan;
        private final Map<String, String> tags;

        /**
         * Creates an experiment cell.
         *
         * @param id stable identifier of the cell
         * @param comparisonGroup identifier of the comparison group this cell belongs to
         * @param candidateId identifier of the candidate within the comparison group
         * @param baseline whether this cell is the baseline of its comparison group
         * @param config explicit configuration of this simulation
         * @param platform platform description whose VM count matches the configuration
         * @param seedPlan seed plan whose replication count and randomization design are
         *                 both declared
         * @param tags optional immutable experiment tags; passing {@code null} is
         *             equivalent to empty tags
         * @throws IllegalArgumentException when an identifier, a required object, the VM
         *         count, or a tag violates its constraint
         */
        public Cell(
                String id,
                String comparisonGroup,
                String candidateId,
                boolean baseline,
                SimulationConfig config,
                PlatformProfile platform,
                SeedPlan seedPlan,
                Map<String, String> tags) {
            this.id = requireIdentifier(id, "Experiment cell ID");
            this.comparisonGroup = requireIdentifier(comparisonGroup, "Comparison group ID");
            this.candidateId = requireIdentifier(candidateId, "Candidate ID");
            this.baseline = baseline;
            this.config = requireNonNull(config, "Simulation configuration");
            this.platform = requireNonNull(platform, "Platform profile");
            if (config.getVmCount() != platform.getVms().size()) {
                throw new IllegalArgumentException("Experiment cell configuration VM count "
                        + config.getVmCount() + " does not match platform VM count "
                        + platform.getVms().size());
            }
            this.seedPlan = requireNonNull(seedPlan, "Seed plan");
            Map<String, String> values = tags == null ? Collections.<String, String>emptyMap() : tags;
            this.tags = Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
            for (Map.Entry<String, String> entry : this.tags.entrySet()) {
                requireIdentifier(entry.getKey(), "Experiment tag key");
                if (entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                    throw new IllegalArgumentException("Experiment tag values cannot be empty");
                }
            }
        }

        public String getId() {
            return id;
        }

        public String getComparisonGroup() {
            return comparisonGroup;
        }

        public String getCandidateId() {
            return candidateId;
        }

        public boolean isBaseline() {
            return baseline;
        }

        public SimulationConfig getConfig() {
            return config;
        }

        public PlatformProfile getPlatform() {
            return platform;
        }

        public SeedPlan getSeedPlan() {
            return seedPlan;
        }

        public Map<String, String> getTags() {
            return tags;
        }
    }

    private static String requireIdentifier(String value, String subject) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(subject + " must match " + IDENTIFIER.pattern());
        }
        return value;
    }

    /** Human-readable caliber description used by the cross-caliber rejection (AUD-F09). */
    private static String describeCostCaliber(AlgorithmCatalog.CostComparisonCaliber caliber, Cell cell) {
        String quantity = caliber == AlgorithmCatalog.CostComparisonCaliber.ONLINE_READY_BATCH
                ? "bare length/MIPS ECT"
                : "TaskExecutionModel matrix seconds";
        return caliber.name() + " (" + quantity + "; planning "
                + cell.getConfig().getPlanningAlgorithm() + ", scheduling "
                + cell.getConfig().getSchedulingAlgorithm() + ")";
    }

    private static <T> T requireNonNull(T value, String subject) {
        if (value == null) {
            throw new IllegalArgumentException(subject + " is required");
        }
        return value;
    }
}
