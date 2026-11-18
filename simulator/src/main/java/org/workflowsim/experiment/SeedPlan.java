package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    public static SeedPlan deterministic(long seed) {
        return new SeedPlan(
                RandomizationDesign.DETERMINISTIC, Long.valueOf(seed), Collections.singletonList(Long.valueOf(seed)));
    }

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
