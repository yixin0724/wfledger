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
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.Log;

public class CloudInformationService extends SimEntity {

    private final List<Integer> resList;

    private final List<Integer> arList;

    private final List<Integer> gisList;

    public CloudInformationService(String name) throws Exception {
        super(name);
        resList = new LinkedList<Integer>();
        arList = new LinkedList<Integer>();
        gisList = new LinkedList<Integer>();
    }

    @Override
    public void startEntity() {}

    @Override
    public void processEvent(SimEvent ev) {
        int id = -1;
        switch (ev.getTag()) {
            case CloudSimTags.REGISTER_REGIONAL_GIS:
                gisList.add((Integer) ev.getData());
                break;

            case CloudSimTags.REQUEST_REGIONAL_GIS:
                id = ((Integer) ev.getData()).intValue();

                super.send(id, 0L, ev.getTag(), gisList);
                break;

            case CloudSimTags.REGISTER_RESOURCE:
                resList.add((Integer) ev.getData());
                break;

            case CloudSimTags.REGISTER_RESOURCE_AR:
                resList.add((Integer) ev.getData());
                arList.add((Integer) ev.getData());
                break;

            case CloudSimTags.RESOURCE_LIST:
                id = ((Integer) ev.getData()).intValue();

                super.send(id, 0L, ev.getTag(), resList);
                break;

            case CloudSimTags.RESOURCE_AR_LIST:
                id = ((Integer) ev.getData()).intValue();

                super.send(id, 0L, ev.getTag(), arList);
                break;

            default:
                processOtherEvent(ev);
                break;
        }
    }

    @Override
    public void shutdownEntity() {
        notifyAllEntity();
    }

    public List<Integer> getList() {
        return resList;
    }

    public List<Integer> getAdvReservList() {
        return arList;
    }

    public boolean resourceSupportAR(Integer id) {
        if (id == null) {
            return false;
        }

        return resourceSupportAR(id.intValue());
    }

    public boolean resourceSupportAR(int id) {
        boolean flag = false;
        if (id < 0) {
            flag = false;
        } else {
            flag = checkResource(arList, id);
        }

        return flag;
    }

    public boolean resourceExist(int id) {
        boolean flag = false;
        if (id < 0) {
            flag = false;
        } else {
            flag = checkResource(resList, id);
        }

        return flag;
    }

    public boolean resourceExist(Integer id) {
        if (id == null) {
            return false;
        }
        return resourceExist(id.intValue());
    }

    protected void processOtherEvent(SimEvent ev) {
        if (ev == null) {
            Log.printLine("CloudInformationService.processOtherEvent(): "
                    + "Unable to handle a request since the event is null.");
            return;
        }

        Log.printLine("CloudInformationSevice.processOtherEvent(): " + "Unable to handle a request from "
                + CloudSim.getEntityName(ev.getSource()) + " with event tag = " + ev.getTag());
    }

    protected void processEndSimulation() {}

    private boolean checkResource(Collection<Integer> list, int id) {
        boolean flag = false;
        if (list == null || id < 0) {
            return flag;
        }

        Integer obj = null;
        Iterator<Integer> it = list.iterator();

        while (it.hasNext()) {
            obj = it.next();
            if (obj.intValue() == id) {
                flag = true;
                break;
            }
        }

        return flag;
    }

    private void notifyAllEntity() {
        Log.printLine(super.getName() + ": Notify all CloudSim entities for shutting down.");

        signalShutdown(resList);
        signalShutdown(gisList);

        resList.clear();
        gisList.clear();
    }

    protected void signalShutdown(Collection<Integer> list) {

        if (list == null) {
            return;
        }

        Iterator<Integer> it = list.iterator();
        Integer obj = null;
        int id = 0;

        while (it.hasNext()) {
            obj = it.next();
            id = obj.intValue();
            super.send(id, 0L, CloudSimTags.END_OF_SIMULATION);
        }
    }
}
