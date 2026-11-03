/**
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.utils.SimulationRandom;

/**
 * Random Task-to-compatible-VM mapping baseline controlled by a random seed.
 *
 * <p>It produces only a mapping: DAG dependencies are still enforced by
 * {@code WorkflowEngine}, and no offline execution order is generated. VM candidates are first
 * sorted stably by ID, and the choice among PE-compatible candidates is then made through
 * {@code SimulationRandom}, so the same seed and identical inputs reproduce.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Jun 17, 2013
 */
public class RandomPlanningAlgorithm extends BasePlanningAlgorithm {

    /** Perform a seed-controlled random mapping over each Task's compatible VM set. */
    @Override
    public void run() {
        Random random = SimulationRandom.newJavaRandom("planning.random");
        List<CondorVM> vms = sortedVms();
        for (Task task : getTaskList()) {
            List<CondorVM> compatible = new ArrayList<CondorVM>();
            for (CondorVM vm : vms) {
                if (task.getNumberOfPes() <= vm.getNumberOfPes()) {
                    compatible.add(vm);
                }
            }
            if (compatible.isEmpty()) {
                throw new IllegalArgumentException("RANDOM cannot map task " + task.getCloudletId()
                        + "; no VM has sufficient processing elements");
            }
            task.setVmId(compatible.get(random.nextInt(compatible.size())).getId());
        }
    }

    private List<CondorVM> sortedVms() {
        if (getVmList() == null || getVmList().isEmpty()) {
            throw new IllegalStateException("RANDOM requires at least one VM");
        }
        List<CondorVM> result = new ArrayList<CondorVM>();
        for (Object item : getVmList()) {
            if (!(item instanceof CondorVM)) {
                throw new IllegalArgumentException("RANDOM requires CondorVM instances");
            }
            result.add((CondorVM) item);
        }
        Collections.sort(result, new Comparator<CondorVM>() {
            @Override
            public int compare(CondorVM first, CondorVM second) {
                return Integer.compare(first.getId(), second.getId());
            }
        });
        return result;
    }
}
