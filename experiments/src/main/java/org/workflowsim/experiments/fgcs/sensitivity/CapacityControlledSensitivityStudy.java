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

/** Fixed-capacity sensitivity grid that keeps algorithm decision layers separate. */
public final class CapacityControlledSensitivityStudy {

    private CapacityControlledSensitivityStudy() {}

    public static List<Case> cases() {
        List<Case> cases = new ArrayList<Case>();
        for (String workflow :
                new String[] {FgcDatasets.MONTAGE_50, FgcDatasets.CYBERSHAKE_50, FgcDatasets.INSPIRAL_50}) {
            for (int vmCount : new int[] {1, 2, 4, 8}) {
                for (int ratio : new int[] {1, 2, 4}) {
                    if (vmCount == 1 && ratio != 1) {
                        continue;
                    }
                    for (int storage : new int[] {1, 15, 100}) {
                        for (Parameters.PlanningAlgorithm planner : staticPlanners()) {
                            cases.add(new Case(
                                    workflow, vmCount, ratio, storage, planner, Parameters.SchedulingAlgorithm.STATIC));
                        }
                        for (Parameters.SchedulingAlgorithm scheduler : onlineSchedulers()) {
                            cases.add(new Case(
                                    workflow,
                                    vmCount,
                                    ratio,
                                    storage,
                                    Parameters.PlanningAlgorithm.INVALID,
                                    scheduler));
                        }
                    }
                }
            }
        }
        return cases;
    }

    public static List<FgcStudySupport.Measurement> run() throws SimulationExecutionException {
        List<FgcStudySupport.Measurement> results = new ArrayList<FgcStudySupport.Measurement>();
        for (Case item : cases()) {
            SimulationConfig config = FgcStudySupport.base(FgcDatasets.real(item.workflow), item.vmCount)
                    .planningAlgorithm(item.planner)
                    .schedulingAlgorithm(item.scheduler)
                    .build();
            results.add(FgcStudySupport.run(
                    item.label(), config, FgcPlatforms.fixedCapacity(item.vmCount, item.ratio, item.storage), null));
        }
        return results;
    }

    private static Parameters.PlanningAlgorithm[] staticPlanners() {
        return new Parameters.PlanningAlgorithm[] {
            Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT,
            Parameters.PlanningAlgorithm.SHARED_STORAGE_CPOP,
            Parameters.PlanningAlgorithm.SHARED_STORAGE_PEFT
        };
    }

    private static Parameters.SchedulingAlgorithm[] onlineSchedulers() {
        return new Parameters.SchedulingAlgorithm[] {
            Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN,
            Parameters.SchedulingAlgorithm.READY_BATCH_MAXMIN,
            Parameters.SchedulingAlgorithm.READY_BATCH_MCT
        };
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0
                ? Paths.get("paper/tmp/fgcs-round2/fixed-capacity-sensitivity/results.json")
                : Paths.get(args[0]);
        List<FgcStudySupport.Measurement> results = run();
        FgcStudySupport.writeResults(output, results);
        System.out.println("FGCS fixed-capacity sensitivity runs=" + results.size());
    }

    static final class Case {
        private final String workflow;
        private final int vmCount;
        private final int ratio;
        private final int storage;
        private final Parameters.PlanningAlgorithm planner;
        private final Parameters.SchedulingAlgorithm scheduler;

        Case(
                String workflow,
                int vmCount,
                int ratio,
                int storage,
                Parameters.PlanningAlgorithm planner,
                Parameters.SchedulingAlgorithm scheduler) {
            this.workflow = workflow;
            this.vmCount = vmCount;
            this.ratio = ratio;
            this.storage = storage;
            this.planner = planner;
            this.scheduler = scheduler;
        }

        String label() {
            return workflow.substring(workflow.lastIndexOf('/') + 1) + "-fc-v" + vmCount + "-r" + ratio
                    + "-b" + storage + "-"
                    + (planner == Parameters.PlanningAlgorithm.INVALID ? scheduler.name() : planner.name());
        }

        Parameters.PlanningAlgorithm getPlanner() {
            return planner;
        }

        Parameters.SchedulingAlgorithm getScheduler() {
            return scheduler;
        }

        int getVmCount() {
            return vmCount;
        }

        int getRatio() {
            return ratio;
        }
    }
}
