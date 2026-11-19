package org.workflowsim.experiments.fgcs.sensitivity;

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

public final class SensitivityStudy {

    private SensitivityStudy() {}

    public static List<FgcStudySupport.Measurement> run() throws SimulationExecutionException {
        List<FgcStudySupport.Measurement> results = new ArrayList<FgcStudySupport.Measurement>();
        for (String workflow :
                new String[] {FgcDatasets.MONTAGE_50, FgcDatasets.CYBERSHAKE_50, FgcDatasets.INSPIRAL_50}) {
            for (int vmCount : new int[] {1, 2, 4, 8}) {
                for (int speedRatio : new int[] {1, 2, 4}) {
                    for (int storageRate : new int[] {1, 15, 100}) {
                        for (Parameters.SchedulingAlgorithm scheduler : new Parameters.SchedulingAlgorithm[] {
                            Parameters.SchedulingAlgorithm.STATIC, Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN
                        }) {
                            SimulationConfig config = FgcStudySupport.base(FgcDatasets.real(workflow), vmCount)
                                    .planningAlgorithm(
                                            scheduler == Parameters.SchedulingAlgorithm.STATIC
                                                    ? Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT
                                                    : Parameters.PlanningAlgorithm.INVALID)
                                    .schedulingAlgorithm(scheduler)
                                    .build();
                            String label = workflow.substring(workflow.lastIndexOf('/') + 1) + "-v" + vmCount + "-r"
                                    + speedRatio + "-b" + storageRate + "-" + scheduler;
                            results.add(FgcStudySupport.run(
                                    label, config, FgcPlatforms.flat(vmCount, speedRatio, storageRate), null));
                        }
                    }
                }
            }
        }
        return results;
    }

    public static void main(String[] args) throws Exception {
        Path output =
                args.length == 0 ? Paths.get("paper/tmp/experiments/sensitivity/results.json") : Paths.get(args[0]);
        List<FgcStudySupport.Measurement> results = run();
        FgcStudySupport.writeResults(output, results);
        System.out.println("FGCS sensitivity runs=" + results.size());
    }
}
