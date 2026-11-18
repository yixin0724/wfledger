package org.workflowsim.experiments.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.experiment.registry.AlgorithmDeclaration;
import org.workflowsim.experiment.registry.SchedulingAlgorithmProvider;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;

public final class CarbonDeferFirstSchedulerProvider implements SchedulingAlgorithmProvider {

    static final String LABEL = "CARBON_DEFER_FIRST";

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        return new CarbonDeferFirstSchedulingAlgorithm();
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .fileSystemScope(AlgorithmDeclaration.FileSystemScope.ANY)
                .allowDataMovementKind("LEGACY_WORKFLOWSIM_V1")
                .acceptsTaskCostMatrix(false)
                .allowsFailureModel(false)
                .powerModelRequired(true)
                .carbonObjectiveWeight(1.0)
                .description("C5-lite demonstrator: budget-window-spirit placement proving the"
                        + " carbon-aware registry acceptance surface and evidence chain; requires a"
                        + " powered profile; a real CWM-style deferral engine is follow-up work")
                .build();
    }

    static final class CarbonDeferFirstSchedulingAlgorithm extends BaseSchedulingAlgorithm {

        @Override
        public void run() {
            validateCloudletCompatibility();
            List<CondorVM> vms = sortedVmsById();
            List<Cloudlet> ready = new ArrayList<Cloudlet>();
            for (java.util.Iterator it = getCloudletList().iterator(); it.hasNext(); ) {
                ready.add((Cloudlet) it.next());
            }

            Collections.sort(ready, new Comparator<Cloudlet>() {
                @Override
                public int compare(Cloudlet a, Cloudlet b) {
                    long delta = b.getCloudletLength() - a.getCloudletLength();
                    if (delta != 0) {
                        return delta > 0 ? 1 : -1;
                    }
                    return Integer.compare(a.getCloudletId(), b.getCloudletId());
                }
            });
            for (Cloudlet cloudlet : ready) {

                CondorVM best = null;
                for (CondorVM vm : vms) {
                    if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE && isCompatible(cloudlet, vm)) {
                        best = vm;
                        break;
                    }
                }
                if (best != null) {
                    best.setState(WorkflowSimTags.VM_STATUS_BUSY);
                    cloudlet.setVmId(best.getId());
                    getScheduledList().add(cloudlet);
                }
            }
        }
    }
}
