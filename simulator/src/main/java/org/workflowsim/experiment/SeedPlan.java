package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable, deterministic root seed sequence used by one experiment cell. */
public final class SeedPlan {

    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;

    private final RandomizationDesign randomizationDesign;
    private final Long derivationRootSeed;
    private final List<Long> seeds;

    private SeedPlan(RandomizationDesign randomizationDesign, Long derivationRootSeed, List<Long> seeds) {
        if (randomizationDesign == null || seeds == null || seeds.isEmpty()) {
            throw new IllegalArgumentException("Randomization design and at least one seed are required");
        }
        if (randomizationDesign == RandomizationDesign.DETERMINISTIC && seeds.size() != 1) {
            throw new IllegalArgumentException("A deterministic seed plan must contain exactly one seed");
        }
        Set<Long> uniqueSeeds = new HashSet<Long>(seeds);
        if (uniqueSeeds.size() != seeds.size()) {
            throw new IllegalArgumentException("A seed plan must not repeat a root seed");
        }
        this.randomizationDesign = randomizationDesign;
        this.derivationRootSeed = derivationRootSeed;
        this.seeds = Collections.unmodifiableList(new ArrayList<Long>(seeds));
    }

    /**
     * Creates a deterministic run plan that contains a single root seed.
     *
     * @param seed root seed used by this deterministic run
     * @return deterministic seed plan with exactly one replication
     */
    public static SeedPlan deterministic(long seed) {
        return new SeedPlan(
                RandomizationDesign.DETERMINISTIC, Long.valueOf(seed), Collections.singletonList(Long.valueOf(seed)));
    }

    /**
     * Deterministically derives multiple seeds from an explicit root seed using the
     * SplitMix64 mixing function.
     *
     * <p>This is reproducible seed derivation, not a random sampling.</p>
     *
     * @param design randomization interpretation adopted by the plan
     * @param rootSeed root seed used for derivation
     * @param replicationCount number of replications to derive, must be positive
     * @return immutable plan containing the derived seed sequence
     * @throws IllegalArgumentException if the design is null, the replication count is not
     *         positive, or a deterministic design has a replication count other than one
     */
    public static SeedPlan derived(RandomizationDesign design, long rootSeed, int replicationCount) {
        if (design == null || replicationCount <= 0) {
            throw new IllegalArgumentException("Randomization design and positive replication count are required");
        }
        if (design == RandomizationDesign.DETERMINISTIC && replicationCount != 1) {
            throw new IllegalArgumentException("A deterministic seed plan requires exactly one replication");
        }
        List<Long> values = new ArrayList<Long>(replicationCount);
        long state = rootSeed;
        for (int index = 0; index < replicationCount; index++) {
            state += GOLDEN_GAMMA;
            values.add(Long.valueOf(mix64(state)));
        }
        return new SeedPlan(design, Long.valueOf(rootSeed), values);
    }

    /**
     * Creates a plan from a root seed sequence fully specified by the caller.
     *
     * @param design randomization interpretation adopted by the plan
     * @param seeds root seed sequence that must be non-repeating and contain at least one
     *        element
     * @return immutable explicit seed plan that records no derivation root
     * @throws IllegalArgumentException if the design or the seed sequence violates the
     *         plan constraints
     */
    public static SeedPlan explicit(RandomizationDesign design, List<Long> seeds) {
        return new SeedPlan(design, null, seeds);
    }

    private static long mix64(long value) {
        long mixed = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        return mixed ^ (mixed >>> 31);
    }

    public RandomizationDesign getRandomizationDesign() {
        return randomizationDesign;
    }

    public Long getDerivationRootSeed() {
        return derivationRootSeed;
    }

    public List<Long> getSeeds() {
        return seeds;
    }

    public int getReplicationCount() {
        return seeds.size();
    }
}
