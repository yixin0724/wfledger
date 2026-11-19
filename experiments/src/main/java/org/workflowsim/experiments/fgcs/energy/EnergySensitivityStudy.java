package org.workflowsim.experiments.fgcs.energy;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.experiments.fgcs.FgcDatasets;
import org.workflowsim.experiments.fgcs.FgcPlatforms;
import org.workflowsim.experiments.fgcs.FgcStudySupport;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

public final class EnergySensitivityStudy {

    private EnergySensitivityStudy() {}

    public static List<FgcStudySupport.Measurement> run() throws SimulationExecutionException {
        List<FgcStudySupport.Measurement> results = new ArrayList<FgcStudySupport.Measurement>();
        for (String workflow : new String[] {
            FgcDatasets.MONTAGE_50,
            FgcDatasets.CYBERSHAKE_50,
            FgcDatasets.SIPHT_60,
            FgcDatasets.INSPIRAL_50,
            FgcDatasets.EPIGENOMICS_46
        }) {
            for (Parameters.PlanningAlgorithm planner : new Parameters.PlanningAlgorithm[] {
                Parameters.PlanningAlgorithm.GREEN_HEFT, Parameters.PlanningAlgorithm.MO_HEFT
            }) {
                SimulationConfig config = FgcStudySupport.base(FgcDatasets.real(workflow), 4)
                        .planningAlgorithm(planner)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .build();
                results.add(FgcStudySupport.run(workflow + "-" + planner, config, FgcPlatforms.flat(4, 1), null));
            }
            SimulationConfig baseline = FgcStudySupport.base(FgcDatasets.real(workflow), 4)
                    .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                    .build();
            FgcStudySupport.Measurement initial =
                    FgcStudySupport.run(workflow + "-default", baseline, FgcPlatforms.tiered(4, 1, false), null);
            results.add(initial);
            long deadline = (long) Math.ceil(initial.getReport().getMakespan() * 1.2);
            for (SimulationConfig.FrequencyPolicy policy : new SimulationConfig.FrequencyPolicy[] {
                SimulationConfig.FrequencyPolicy.MIN_POWER, SimulationConfig.FrequencyPolicy.EDP_HEURISTIC
            }) {
                SimulationConfig config = FgcStudySupport.base(FgcDatasets.real(workflow), 4)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .frequencyPolicy(policy)
                        .build();
                results.add(
                        FgcStudySupport.run(workflow + "-" + policy, config, FgcPlatforms.tiered(4, 1, false), null));
            }
            SimulationConfig dvfs = FgcStudySupport.base(FgcDatasets.real(workflow), 4)
                    .planningAlgorithm(Parameters.PlanningAlgorithm.DVFS_SLACK)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.DVFS_SLACK)
                    .deadline(deadline)
                    .build();
            results.add(FgcStudySupport.run(workflow + "-dvfs", dvfs, FgcPlatforms.tiered(4, 1, false), null));
        }
        for (String workflow : new String[] {FgcDatasets.MONTAGE_50, FgcDatasets.CYBERSHAKE_50}) {
            for (SimulationConfig.FrequencyPolicy policy : new SimulationConfig.FrequencyPolicy[] {
                SimulationConfig.FrequencyPolicy.DEFAULT,
                SimulationConfig.FrequencyPolicy.MIN_POWER,
                SimulationConfig.FrequencyPolicy.EDP_HEURISTIC
            }) {
                SimulationConfig config = FgcStudySupport.base(FgcDatasets.real(workflow), 4)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .frequencyPolicy(policy)
                        .build();
                results.add(FgcStudySupport.run(
                        workflow + "-nonmonotonic-" + policy, config, FgcPlatforms.tiered(4, 1, true), null));
            }
        }
        return results;
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? Paths.get("paper/tmp/experiments/energy/results.json") : Paths.get(args[0]);
        List<FgcStudySupport.Measurement> results = run();
        FgcStudySupport.writeResults(output, results);
        System.out.println("FGCS energy sensitivity runs=" + results.size());
    }
}
