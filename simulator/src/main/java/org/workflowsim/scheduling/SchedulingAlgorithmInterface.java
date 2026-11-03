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

import java.util.List;

/**
 * Minimal interface for a scheduling policy.
 *
 * <p>The caller supplies the current ready Job and VM lists at each scheduling event, and the
 * policy returns only the Jobs dispatched in that round; the interface itself defines neither
 * offline plans, future availability times, nor realistic queueing semantics.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public interface SchedulingAlgorithmInterface {

    /** Set the current ready Job list. */
    public void setCloudletList(List list);

    /** Set the current VM list. */
    public void setVmList(List list);

    /** Return the current ready Job list. */
    public List getCloudletList();

    /** Return the current VM list. */
    public List getVmList();

    /** Execute one scheduling update. */
    public void run() throws Exception;

    /** Return the Jobs dispatched in this round. */
    public List getScheduledList();
}
