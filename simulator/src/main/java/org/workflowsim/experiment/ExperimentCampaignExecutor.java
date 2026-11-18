package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.List;

public final class ExperimentCampaignExecutor {

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
