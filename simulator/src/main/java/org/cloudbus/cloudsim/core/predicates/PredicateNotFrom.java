/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.core.predicates;

import org.cloudbus.cloudsim.core.SimEvent;

public class PredicateNotFrom extends Predicate {

    private final int[] ids;

    public PredicateNotFrom(int sourceId) {
        ids = new int[] {sourceId};
    }

    public PredicateNotFrom(int[] sourceIds) {
        ids = sourceIds.clone();
    }

    @Override
    public boolean match(SimEvent ev) {
        int src = ev.getSource();
        for (int id : ids) {
            if (src == id) {
                return false;
            }
        }
        return true;
    }
}
