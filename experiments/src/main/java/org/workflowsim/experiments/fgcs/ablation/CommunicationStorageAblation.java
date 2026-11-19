package org.workflowsim.experiments.fgcs.ablation;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

public final class CommunicationStorageAblation {

    private CommunicationStorageAblation() {}

    public static List<FgcStudySupport.Measurement> run(Path evidenceRoot) throws SimulationExecutionException {
        List<FgcStudySupport.Measurement> results = new ArrayList<FgcStudySupport.Measurement>();
        for (String shape : new String[] {"chain", "fan"}) {
            try {
                String workflow = FgcDatasets.smallDag(evidenceRoot.resolve("inputs"), shape)
                        .toString();
                for (DataMovementModel model : new DataMovementModel[] {
                    DataMovementModel.preExecutionTransferDelayV1(),
                    DataMovementModel.preExecutionTransferDelayWithContentionV1(),
                    DataMovementModel.fatTreeContentionV1()
                }) {
                    SimulationConfig config = FgcStudySupport.base(workflow, 4)
                            .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                            .dataMovementModel(model)
                            .networkEvidence(
                                    model == DataMovementModel.preExecutionTransferDelayV1()
                                            ? NetworkEvidenceConfig.off()
                                            : NetworkEvidenceConfig.fluidGroupLedger(10000))
                            .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                            .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                            .build();
                    results.add(FgcStudySupport.run(
                            shape + "-" + model.getKind(),
                            config,
                            model == DataMovementModel.fatTreeContentionV1()
                                    ? FgcPlatforms.fatTree(4, 2, 4, 100.0)
                                    : FgcPlatforms.flat(4, 2),
                            evidenceRoot.resolve(shape + "-" + model.getKind())));
                }
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Cannot generate ablation DAG " + shape, exception);
            }
        }
        for (boolean coherentStorage : new boolean[] {false, true}) {
            for (boolean sharedResources : new boolean[] {false, true}) {
                for (ReplicaCatalog.FileSystem fileSystem : new ReplicaCatalog.FileSystem[] {
                    ReplicaCatalog.FileSystem.LOCAL, ReplicaCatalog.FileSystem.SHARED
                }) {
                    if (!coherentStorage && fileSystem == ReplicaCatalog.FileSystem.SHARED) {
                        continue;
                    }
                    DataMovementModel model;
                    NetworkEvidenceConfig evidence;
                    if (coherentStorage) {
                        model = sharedResources
                                ? DataMovementModel.coherentStorageDataflowV3()
                                : DataMovementModel.coherentStorageDataflowNoContentionV3();
                        evidence = NetworkEvidenceConfig.storageLifecycleV3(10000);
                    } else {
                        model = sharedResources
                                ? DataMovementModel.coherentFileDataflowV2()
                                : DataMovementModel.coherentFileDataflowNoContentionV2();
                        evidence = NetworkEvidenceConfig.fileLifecycleV2(10000);
                    }
                    for (boolean topology : new boolean[] {false, true}) {
                        String workflow;
                        try {
                            workflow = FgcDatasets.smallDag(evidenceRoot.resolve("inputs"), "fan")
                                    .toString();
                        } catch (java.io.IOException exception) {
                            throw new IllegalStateException("Cannot generate coherent ablation DAG", exception);
                        }
                        SimulationConfig config = FgcStudySupport.base(workflow, 4)
                                .fileSystem(fileSystem)
                                .dataMovementModel(model)
                                .networkEvidence(evidence)
                                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                                .build();
                        String label = (coherentStorage ? "storage" : "coherent") + "-shared" + sharedResources + "-fs"
                                + fileSystem + "-fat" + topology;
                        results.add(FgcStudySupport.run(
                                label,
                                config,
                                topology
                                        ? coherentStorage
                                                ? FgcPlatforms.storageFatTree(4, 2, 4, 100.0)
                                                : FgcPlatforms.fatTree(4, 2, 4, 100.0)
                                        : coherentStorage ? FgcPlatforms.storage(4, 2) : FgcPlatforms.flat(4, 2),
                                evidenceRoot.resolve(label)));
                    }
                }
            }
        }
        return results;
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? Paths.get("paper/tmp/experiments/ablation/results.json") : Paths.get(args[0]);
        List<FgcStudySupport.Measurement> results = run(Paths.get("paper/tmp/experiments/ablation/evidence"));
        FgcStudySupport.writeResults(output, results);
        System.out.println("FGCS communication/storage ablation runs=" + results.size());
    }
}
