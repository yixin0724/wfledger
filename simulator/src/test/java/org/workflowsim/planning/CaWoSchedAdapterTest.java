package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.planning.cawosched.CaWoSchedAdapter;
import org.workflowsim.planning.cawosched.CaWoSchedPlan;
import org.workflowsim.planning.cawosched.CaWoSchedPlanner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;

/** Verifies the actual SHARED HEFT to CaWoSched fixed-plan adaptation (R-PAPER-01). */
class CaWoSchedAdapterTest {

    @Test
    void adaptsHeftMappingOrderDurationsAndDependencies() {
        Task first = new Task(1, 4L);
        Task second = new Task(2, 2L);
        PlanningRegressionFixtures.connect(first, second);
        List<Task> tasks = Arrays.asList(first, second);
        List<CondorVM> vms = PlanningRegressionFixtures.vms(2.0, 1, 1.0, 1);
        PlatformProfile platform = PlanningRegressionFixtures.platform(vms);
        PlanningContext context = new PlanningContext(
                PlanningRegressionFixtures.config(
                        "cawosched-adapter.dax", Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT, null, false),
                platform);
        SharedStorageHeftPlanningAlgorithm heft = new SharedStorageHeftPlanningAlgorithm(context);
        heft.setTaskList(tasks);
        heft.setVmList(vms);
        heft.run();
        SharedStorageDagPlanTrace trace = heft.getLastPlanTrace();
        List<PlatformProfile.CarbonWindow> windows = Arrays.asList(
                new PlatformProfile.CarbonWindow(0.0, 5.0, 0.0),
                new PlatformProfile.CarbonWindow(5.0, 10.0, 100.0),
                new PlatformProfile.CarbonWindow(10.0, 20.0, 0.0));
        CaWoSchedPlanner planner = CaWoSchedAdapter.fromSharedStoragePlan(tasks, vms, platform, trace, windows, 20.0);

        CaWoSchedPlan plan = planner.plan(CaWoSchedPlanner.Score.SLACK);
        CaWoSchedPlan asap = planner.planAsap();

        for (Task task : tasks) {
            assertEquals(
                    trace.getTaskPlan(task.getCloudletId()).getVmId(),
                    plan.getTaskPlan(task.getCloudletId()).getVmId());
            double mips = task.getVmId() == 3 ? 2.0 : 1.0;
            assertEquals(
                    task.getCloudletLength() / mips,
                    plan.getTaskPlan(task.getCloudletId()).getDurationSeconds());
        }
        assertTrue(plan.getTaskPlan(1).getFinishSeconds() <= plan.getTaskPlan(2).getStartSeconds());
        assertTrue(plan.getMakespanSeconds() <= 20.0);
        assertTrue(plan.getCarbonExcessWattSeconds() >= 0.0);
        assertTrue(asap.getCarbonExcessWattSeconds() >= 0.0);
    }
}
