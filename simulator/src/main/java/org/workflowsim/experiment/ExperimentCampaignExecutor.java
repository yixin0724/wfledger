package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.List;

/** Execute the declared campaigns sequentially through the standard runner. */
public final class ExperimentCampaignExecutor {

    /**
     * Run every repetition in cell declaration order and seed-plan order.
     *
     * <p>WorkflowSim retains the CloudSim static state and the standard runner does not
     * promise in-JVM concurrency, so sequential execution is deliberate here.</p>
     *
     * @param plan immutable experiment plan already validated against the construction constraints
     * @return immutable results covering every executed cell/repetition
     * @throws Exception when any standard simulation run fails
     * @throws IllegalArgumentException when {@code plan} is null
     */
    public ExperimentCampaignResult execute(ExperimentPlan plan) throws Exception {
        if (plan == null) {
            throw new IllegalArgumentException("Experiment plan is required");
        }
        SimulationRunner runner = new SimulationRunner();
        List<ExperimentCampaignResult.Run> runs = new ArrayList<ExperimentCampaignResult.Run>();
        for (ExperimentPlan.Cell cell : plan.getCells()) {
            List<Long> seeds = cell.getSeedPlan().getSeeds();
            for (int replication = 0; replication < seeds.size(); replication++) {
                long seed = seeds.get(replication).longValue();
                SimulationReport report = runner.run(cell.getConfig().withRandomSeed(seed), cell.getPlatform());
                runs.add(new ExperimentCampaignResult.Run(cell, replication, seed, report));
            }
        }
        return new ExperimentCampaignResult(plan, runs);
    }
}
