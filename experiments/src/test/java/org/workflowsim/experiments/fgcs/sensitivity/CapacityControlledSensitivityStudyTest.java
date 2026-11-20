package org.workflowsim.experiments.fgcs.sensitivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.experiments.fgcs.FgcPlatforms;
import org.workflowsim.experiments.fgcs.FgcStudySupport;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;

class CapacityControlledSensitivityStudyTest {

    @Test
    void gridHas540CellsAndNoDuplicateSingleVmRatios() {
        List<CapacityControlledSensitivityStudy.Case> cases = CapacityControlledSensitivityStudy.cases();
        assertEquals(540, cases.size());
        assertTrue(cases.stream().allMatch(item -> item.getVmCount() > 1 || item.getRatio() == 1));
        assertTrue(cases.stream()
                .allMatch(item -> (item.getPlanner() == Parameters.PlanningAlgorithm.INVALID)
                        == (item.getScheduler() != Parameters.SchedulingAlgorithm.STATIC)));
    }

    @Test
    void everyPlatformHasFixedTotalMipsPerVmCount() {
        for (int vmCount : new int[] {1, 2, 4, 8}) {
            for (int ratio : new int[] {1, 2, 4}) {
                PlatformProfile profile = FgcPlatforms.fixedCapacity(vmCount, ratio, 15);
                double total = profile.getVms().stream()
                        .mapToDouble(PlatformProfile.VmSpec::getMips)
                        .sum();
                assertEquals(500.0 * vmCount, total, 1e-9);
                for (int index = 0; index < vmCount; index++) {
                    double vmMips = profile.getVms().get(index).getMips();
                    double hostMips = profile.getHosts().get(index).getMipsPerPe();
                    assertTrue(hostMips >= vmMips && hostMips - vmMips < 1.0);
                }
            }
        }
    }

    @Test
    void oneActualCellUsesTheDeclaredStaticLayer() throws Exception {
        CapacityControlledSensitivityStudy.Case item =
                CapacityControlledSensitivityStudy.cases().get(0);
        org.workflowsim.utils.SimulationConfig config = org.workflowsim.experiments.fgcs.FgcStudySupport.base(
                        org.workflowsim.experiments.fgcs.FgcDatasets.real(
                                org.workflowsim.experiments.fgcs.FgcDatasets.MONTAGE_50),
                        1)
                .planningAlgorithm(item.getPlanner())
                .schedulingAlgorithm(item.getScheduler())
                .build();
        FgcStudySupport.Measurement result =
                FgcStudySupport.run(item.label(), config, FgcPlatforms.fixedCapacity(1, 1, 1), null);
        assertTrue(result.getReport().isWorkflowCompletedSuccessfully());
        assertEquals(
                Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT,
                result.getReport().getConfig().getPlanningAlgorithm());
        assertEquals(
                Parameters.SchedulingAlgorithm.STATIC,
                result.getReport().getConfig().getSchedulingAlgorithm());
    }
}
