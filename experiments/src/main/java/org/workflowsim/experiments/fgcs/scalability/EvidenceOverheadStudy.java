package org.workflowsim.experiments.fgcs.scalability;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.experiments.fgcs.FgcDatasets;
import org.workflowsim.experiments.fgcs.FgcPlatforms;
import org.workflowsim.experiments.fgcs.FgcStudySupport;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

public final class EvidenceOverheadStudy {

    private EvidenceOverheadStudy() {}

    public static List<String> workflows() {
        return Arrays.asList(
                FgcDatasets.MONTAGE_50,
                FgcDatasets.MONTAGE_1000,
                "experiments/studies/makespan-scale/inputs/Montage_5000.dax");
    }

    public static FgcStudySupport.Measurement run(String workflow, String mode, int repetition, Path evidenceRoot)
            throws SimulationExecutionException {
        String input;
        try {
            input = FgcDatasets.normalizeForStorage(workflow, evidenceRoot.resolve("inputs"))
                    .toString();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot derive bounded-storage overhead input", exception);
        }
        SimulationConfig.Builder builder = FgcStudySupport.base(input, 4)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.coherentStorageDataflowV3())
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC);
        Path evidence = null;
        if ("off".equals(mode)) {
            builder.eventRecordingEnabled(false);
        } else if ("events".equals(mode)) {
            evidence = evidenceRoot.resolve("events-" + repetition);
        } else if ("lifecycle".equals(mode)) {
            builder.networkEvidence(NetworkEvidenceConfig.storageLifecycleV3(2000000));
            evidence = evidenceRoot.resolve("lifecycle-" + repetition);
        } else {
            throw new IllegalArgumentException("Unknown overhead mode " + mode);
        }
        return FgcStudySupport.run(
                workflow + "-" + mode + "-r" + repetition, builder.build(), FgcPlatforms.storage(4, 1), evidence);
    }

    public static List<FgcStudySupport.Measurement> run(Path evidenceRoot, int repetitions)
            throws SimulationExecutionException {
        List<FgcStudySupport.Measurement> results = new ArrayList<FgcStudySupport.Measurement>();
        for (String workflow : workflows()) {
            for (String mode : new String[] {"off", "events", "lifecycle"}) {
                for (int repetition = 0; repetition < repetitions; repetition++) {
                    results.add(run(workflow, mode, repetition, evidenceRoot));
                }
            }
        }
        return results;
    }

    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args.length == 0 ? "paper/tmp/fgcs-revision/overhead/results.json" : args[0]);
        if (args.length >= 5) {
            String workflow = args[1];
            String mode = args[2];
            int repetition = Integer.parseInt(args[3]);
            Path evidence = Paths.get(args[4]);
            run(workflow, mode, -1, evidence.resolve("warmup"));
            FgcStudySupport.writeResults(output, Collections.singletonList(run(workflow, mode, repetition, evidence)));
            System.out.println("FGCS independent overhead scenario completed");
            return;
        }
        int repetitions = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        List<FgcStudySupport.Measurement> results = new ArrayList<FgcStudySupport.Measurement>();
        for (String workflow : workflows()) {
            for (String mode : new String[] {"off", "events", "lifecycle"}) {
                run(workflow, mode, -1, output.getParent().resolve("warmup"));
                for (int repetition = 0; repetition < repetitions; repetition++) {
                    results.add(
                            run(workflow, mode, repetition, output.getParent().resolve("evidence")));
                }
            }
        }
        FgcStudySupport.writeResults(output, results);
        System.out.println("FGCS evidence overhead runs=" + results.size());
    }
}
