/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.util.List;
import java.util.Map;

public abstract class VmAllocationPolicy {

    private List<? extends Host> hostList;

    public VmAllocationPolicy(List<? extends Host> list) {
        setHostList(list);
    }

    public abstract boolean allocateHostForVm(Vm vm);

    public abstract boolean allocateHostForVm(Vm vm, Host host);

    public abstract List<Map<String, Object>> optimizeAllocation(List<? extends Vm> vmList);

    public abstract void deallocateHostForVm(Vm vm);

    public abstract Host getHost(Vm vm);

    public abstract Host getHost(int vmId, int userId);

    protected void setHostList(List<? extends Host> hostList) {
        this.hostList = hostList;
    }

    @SuppressWarnings("unchecked")
    public <T extends Host> List<T> getHostList() {
        return (List<T>) hostList;
    }
}
