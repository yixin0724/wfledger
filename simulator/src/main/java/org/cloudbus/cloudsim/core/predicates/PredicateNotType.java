/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.core.predicates;

import org.cloudbus.cloudsim.core.SimEvent;

public class PredicateNotType extends Predicate {

    private final int[] tags;

    public PredicateNotType(int tag) {
        tags = new int[] {tag};
    }

    public PredicateNotType(int[] tags) {
        this.tags = tags.clone();
    }

    @Override
    public boolean match(SimEvent ev) {
        int tag = ev.getTag();
        for (int tag2 : tags) {
            if (tag == tag2) {
                return false;
            }
        }
        return true;
    }
}
