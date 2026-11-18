/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.core;

import java.util.Collection;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;

public class FutureQueue {

    private final SortedSet<SimEvent> sortedSet = new TreeSet<SimEvent>();

    private long serial = 0;

    public void addEvent(SimEvent newEvent) {
        newEvent.setSerial(serial++);
        sortedSet.add(newEvent);
    }

    public void addEventFirst(SimEvent newEvent) {
        newEvent.setSerial(0);
        sortedSet.add(newEvent);
    }

    public Iterator<SimEvent> iterator() {
        return sortedSet.iterator();
    }

    public int size() {
        return sortedSet.size();
    }

    public boolean remove(SimEvent event) {
        return sortedSet.remove(event);
    }

    public boolean removeAll(Collection<SimEvent> events) {
        return sortedSet.removeAll(events);
    }

    public void clear() {
        sortedSet.clear();
    }
}
