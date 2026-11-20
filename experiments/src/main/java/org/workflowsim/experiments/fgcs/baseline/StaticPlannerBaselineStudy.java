package org.workflowsim.experiments.fgcs.baseline;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.experiments.fgcs.FgcDatasets;
import org.workflowsim.experiments.fgcs.FgcStudySupport;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

public final class StaticPlannerBaselineStudy {

    private StaticPlannerBaselineStudy() {}

    public static List<FgcStudySupport.Measurement> run() throws SimulationExecutionException {
        List<FgcStudySupport.Measurement> results = new ArrayList<FgcStudySupport.Measurement>();
        String[] workflows = {
            FgcDatasets.MONTAGE_50,
            "datasets/dax/montage/n100/Montage_100.dax",
            FgcDatasets.CYBERSHAKE_50,
            "datasets/dax/cybershake/n100/CyberShake_100.dax",
            FgcDatasets.SIPHT_60,
            FgcDatasets.INSPIRAL_50,
            FgcDatasets.EPIGENOMICS_46
        };
        for (String workflow : workflows) {
            for (Parameters.PlanningAlgorithm planner : new Parameters.PlanningAlgorithm[] {
                Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT,
                Parameters.PlanningAlgorithm.SHARED_STORAGE_CPOP,
                Parameters.PlanningAlgorithm.SHARED_STORAGE_PEFT
            }) {
                SimulationConfig config = FgcStudySupport.base(FgcDatasets.real(workflow), 4)
                        .planningAlgorithm(planner)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .build();
                results.add(FgcStudySupport.run(workflow + "-" + planner, config, baselinePlatform(), null));
            }
        }
        return results;
    }

    public static PlatformProfile baselinePlatform() {
        return org.workflowsim.experiments.makespan.MakespanBaselineComparison.platform();
    }

    public static void main(String[] args) throws Exception {
        Path output =
                args.length == 0 ? Paths.get("paper/tmp/fgcs-round2/static-baseline/results.json") : Paths.get(args[0]);
        FgcStudySupport.writeResults(output, run());
        System.out.println("FGCS same-layer static baseline runs=21");
    }
}
