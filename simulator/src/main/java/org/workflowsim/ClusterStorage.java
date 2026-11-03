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
package org.workflowsim;

import java.util.HashMap;
import java.util.Map;

import org.cloudbus.cloudsim.HarddriveStorage;
import org.cloudbus.cloudsim.ParameterException;

/**
 * Clustered or VM-local storage model.
 *
 * <p>In addition to the capacity model of {@link HarddriveStorage}, this class maintains the
 * logical bandwidth to other storage locations. It is part of the static network
 * approximation and does not model link queues, bandwidth contention, or protocol
 * overhead.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class ClusterStorage extends HarddriveStorage {

    /** Logical bandwidth map from the current storage location to other locations. */
    Map<String, Double> bandwidthMap;

    /**
     * Creates a storage instance.
     *
     * @param name storage name
     * @param capacity storage capacity
     * @throws ParameterException when the capacity or the storage parameters are invalid
     */
    public ClusterStorage(String name, double capacity) throws ParameterException {
        super(name, capacity);
    }

    /**
     * Sets the logical bandwidth to a destination storage location.
     *
     * @param name destination storage name
     * @param bandwidth bandwidth; negative values are ignored
     */
    public final void setBandwidth(String name, double bandwidth) {
        if (bandwidth >= 0) {
            if (bandwidthMap == null) {
                bandwidthMap = new HashMap<>();
            }
            bandwidthMap.put(name, bandwidth);
        }
    }

    /**
     * Returns the logical bandwidth to a destination storage location.
     *
     * <p>Falls back to the {@code local} bandwidth when no dedicated link is configured.</p>
     *
     * @param destination destination storage name
     * @return the configured maximum bandwidth
     */
    public double getMaxBandwidth(String destination) {
        if (bandwidthMap.containsKey(destination)) {
            return bandwidthMap.get(destination);
        } else {
            // No dedicated link is configured: use the local bandwidth approximation
            // between virtual machines.
            return bandwidthMap.get("local");
        }
    }
}
