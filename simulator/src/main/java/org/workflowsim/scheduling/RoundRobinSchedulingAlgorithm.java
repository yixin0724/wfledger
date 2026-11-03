/**
 * Copyright 2013-2014 University Of Southern California
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
package org.workflowsim.scheduling;

/**
 * Originally named RoundRobinSchedulingAlgorithm.
 *
 * <p>Note: this implementation is not classic Round-Robin. The declared vmIndex is dead code;
 * the actual behavior is First-Fit-Idle (sort by vmId, then take the first idle VM from the
 * head). For ready-batch Round-Robin see
 * {@link ReadyBatchRoundRobinSchedulingAlgorithm}.</p>
 *
 * <p><strong>Compatibility boundary:</strong> retained solely for historical source
 * compatibility; the standard research entry point rejects this label, and results of this
 * class must not be claimed as classic Round-Robin.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date May 12, 2014
 * @deprecated vmIndex is dead code and the behavior is actually First-Fit-Idle; renamed to
 *             {@link FirstFitIdleSchedulingAlgorithm}.
 *             The ready-batch implementation is
 *             {@link ReadyBatchRoundRobinSchedulingAlgorithm}.
 */
@Deprecated
public class RoundRobinSchedulingAlgorithm extends FirstFitIdleSchedulingAlgorithm {

    public RoundRobinSchedulingAlgorithm() {
        super();
    }
}
