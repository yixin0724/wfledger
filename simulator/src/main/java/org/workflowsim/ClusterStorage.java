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

public class ClusterStorage extends HarddriveStorage {

    Map<String, Double> bandwidthMap;

    public ClusterStorage(String name, double capacity) throws ParameterException {
        super(name, capacity);
    }

    public final void setBandwidth(String name, double bandwidth) {
        if (bandwidth >= 0) {
            if (bandwidthMap == null) {
                bandwidthMap = new HashMap<>();
            }
            bandwidthMap.put(name, bandwidth);
        }
    }

    public double getMaxBandwidth(String destination) {
        if (bandwidthMap.containsKey(destination)) {
            return bandwidthMap.get(destination);
        } else {

            return bandwidthMap.get("local");
        }
    }
}
