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
package org.workflowsim.scheduling;

/**
 * Originally named MinMinSchedulingAlgorithm.
 *
 * <p>Note: this implementation is not the classic Min-Min (Braun et al. 2001). The actual
 * behavior is an SPT (Shortest Processing Time) - fastest-idle variant: first select the
 * shortest task, then select the fastest idle VM. For ready-batch Min-Min see
 * {@link ReadyBatchMinMinSchedulingAlgorithm}.</p>
 *
 * <p><strong>Compatibility boundary:</strong> retained solely for historical source
 * compatibility; the standard research entry point rejects this label, and results of this
 * class must not be claimed as classic Min-Min.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 * @deprecated Behavior is inconsistent with classic Min-Min; renamed to
 *             {@link SptFastestIdleSchedulingAlgorithm}.
 *             The ready-batch implementation is
 *             {@link ReadyBatchMinMinSchedulingAlgorithm}.
 */
@Deprecated
public class MinMinSchedulingAlgorithm extends SptFastestIdleSchedulingAlgorithm {

    public MinMinSchedulingAlgorithm() {
        super();
    }
}
