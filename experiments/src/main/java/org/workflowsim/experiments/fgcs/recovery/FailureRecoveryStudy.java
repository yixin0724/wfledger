package org.workflowsim.experiments.fgcs.recovery;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.experiments.fgcs.FgcDatasets;
import org.workflowsim.experiments.fgcs.FgcPlatforms;
import org.workflowsim.experiments.fgcs.FgcStudySupport;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.CrchConfig;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

public final class FailureRecoveryStudy {

    private FailureRecoveryStudy() {}

    public static List<FgcStudySupport.Measurement> run(Path generatedInputs) throws SimulationExecutionException {
        List<FgcStudySupport.Measurement> results = new ArrayList<FgcStudySupport.Measurement>();
        for (String shape : new String[] {"chain", "fan", "independent"}) {
            try {
                String workflow = FgcDatasets.smallDag(generatedInputs, shape).toString();
                addPair(results, shape, workflow, 10.0);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Cannot generate recovery DAG " + shape, exception);
            }
        }
        for (String workflow : new String[] {FgcDatasets.MONTAGE_50, FgcDatasets.CYBERSHAKE_50, FgcDatasets.SIPHT_60}) {
            addPair(results, workflow.substring(workflow.lastIndexOf('/') + 1), FgcDatasets.real(workflow), 1000.0);
        }
        return results;
    }

    private static void addPair(
            List<FgcStudySupport.Measurement> results, String label, String workflow, double failureScale)
            throws SimulationExecutionException {
        for (boolean failures : new boolean[] {false, true}) {
            SimulationConfig config = FgcStudySupport.base(workflow, 4)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.CRCH)
                    .crchConfig(new CrchConfig(1.0, 0.1))
                    .failureModel(failures ? singleVmFailure(failureScale) : FailureModelConfig.disabled())
                    .build();
            results.add(FgcStudySupport.run(
                    label + (failures ? "-crch-failure" : "-no-failure"), config, FgcPlatforms.flat(4, 2), null));
        }
    }

    private static FailureModelConfig singleVmFailure(double failureScale) {
        Map<Integer, DistributionSpec[]> specs = new LinkedHashMap<Integer, DistributionSpec[]>();
        for (int vmId : new int[] {1, 2, 4}) {
            specs.put(vmId, new DistributionSpec[] {
                DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 1000000000.0, 1.0)
            });
        }
        specs.put(3, new DistributionSpec[] {
            DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, failureScale, 1.0)
        });
        return FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_CRCH)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_ALL)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
                .distributionFamily(DistributionGenerator.DistributionFamily.WEIBULL)
                .generatorSpecsByVmId(specs)
                .maxTotalRetryJobs(20)
                .build();
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? Paths.get("paper/tmp/experiments/recovery/results.json") : Paths.get(args[0]);
        List<FgcStudySupport.Measurement> results = run(Paths.get("paper/tmp/experiments/recovery/inputs"));
        FgcStudySupport.writeResults(output, results);
        System.out.println("FGCS recovery runs=" + results.size());
    }
}
