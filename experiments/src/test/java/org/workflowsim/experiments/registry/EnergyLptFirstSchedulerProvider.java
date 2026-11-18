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

public final class EnergyLptFirstSchedulerProvider implements SchedulingAlgorithmProvider {

    static final String LABEL = "ENERGY_LPT_FIRST";

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        return new EnergyLptFirstSchedulingAlgorithm();
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .fileSystemScope(AlgorithmDeclaration.FileSystemScope.ANY)
                .allowDataMovementKind("LEGACY_WORKFLOWSIM_V1")
                .acceptsTaskCostMatrix(false)
                .allowsFailureModel(false)
                .powerModelRequired(true)
                .energyObjectiveWeight(1.0)
                .description("E5 demonstrator: longest-job-first placement minimizing makespan"
                        + " (hence EDP, by power-model monotonicity); requires a powered profile")
                .build();
    }

    static final class EnergyLptFirstSchedulingAlgorithm extends BaseSchedulingAlgorithm {

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
                        if (best == null || vm.getMips() > best.getMips()) {
                            best = vm;
                        }
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
