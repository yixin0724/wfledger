/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.core;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

public class DeferredQueue {

    private final List<SimEvent> list = new LinkedList<SimEvent>();

    private double maxTime = -1;

    public void addEvent(SimEvent newEvent) {

        double eventTime = newEvent.eventTime();
        if (eventTime >= maxTime) {
            list.add(newEvent);
            maxTime = eventTime;
            return;
        }

        ListIterator<SimEvent> iterator = list.listIterator();
        SimEvent event;
        while (iterator.hasNext()) {
            event = iterator.next();
            if (event.eventTime() > eventTime) {
                iterator.previous();
                iterator.add(newEvent);
                return;
            }
        }

        list.add(newEvent);
    }

    public Iterator<SimEvent> iterator() {
        return list.iterator();
    }

    public int size() {
        return list.size();
    }

    public void clear() {
        list.clear();
    }
}
