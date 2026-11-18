/*
 * Title: CloudSim Toolkit Description: CloudSim (Cloud Simulation) Toolkit for Modeling and
 * Simulation of Clouds Licence: GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.lists.PeList;
import org.cloudbus.cloudsim.provisioners.PeProvisioner;

public class VmSchedulerTimeShared extends VmScheduler {

    private Map<String, List<Double>> mipsMapRequested;

    private int pesInUse;

    public VmSchedulerTimeShared(List<? extends Pe> pelist) {
        super(pelist);
        setMipsMapRequested(new HashMap<String, List<Double>>());
    }

    @Override
    public boolean allocatePesForVm(Vm vm, List<Double> mipsShareRequested) {

        if (vm.isInMigration()) {
            if (!getVmsMigratingIn().contains(vm.getUid())
                    && !getVmsMigratingOut().contains(vm.getUid())) {
                getVmsMigratingOut().add(vm.getUid());
            }
        } else {
            if (getVmsMigratingOut().contains(vm.getUid())) {
                getVmsMigratingOut().remove(vm.getUid());
            }
        }
        boolean result = allocatePesForVm(vm.getUid(), mipsShareRequested);
        updatePeProvisioning();
        return result;
    }

    protected boolean allocatePesForVm(String vmUid, List<Double> mipsShareRequested) {
        double totalRequestedMips = 0;
        double peMips = getPeCapacity();
        for (Double mips : mipsShareRequested) {

            if (mips > peMips) {
                return false;
            }
            totalRequestedMips += mips;
        }

        if (getAvailableMips() < totalRequestedMips) {
            return false;
        }

        getMipsMapRequested().put(vmUid, mipsShareRequested);
        setPesInUse(getPesInUse() + mipsShareRequested.size());

        if (getVmsMigratingIn().contains(vmUid)) {

            totalRequestedMips *= 0.1;
        }

        List<Double> mipsShareAllocated = new ArrayList<Double>();
        for (Double mipsRequested : mipsShareRequested) {
            if (getVmsMigratingOut().contains(vmUid)) {

                mipsRequested *= 0.9;
            } else if (getVmsMigratingIn().contains(vmUid)) {

                mipsRequested *= 0.1;
            }
            mipsShareAllocated.add(mipsRequested);
        }

        getMipsMap().put(vmUid, mipsShareAllocated);
        setAvailableMips(getAvailableMips() - totalRequestedMips);

        return true;
    }

    protected void updatePeProvisioning() {
        getPeMap().clear();
        for (Pe pe : getPeList()) {
            pe.getPeProvisioner().deallocateMipsForAllVms();
        }

        Iterator<Pe> peIterator = getPeList().iterator();
        Pe pe = peIterator.next();
        PeProvisioner peProvisioner = pe.getPeProvisioner();
        double availableMips = peProvisioner.getAvailableMips();

        for (Map.Entry<String, List<Double>> entry : getMipsMap().entrySet()) {
            String vmUid = entry.getKey();
            getPeMap().put(vmUid, new LinkedList<Pe>());

            for (double mips : entry.getValue()) {
                while (mips >= 0.1) {
                    if (availableMips >= mips) {
                        peProvisioner.allocateMipsForVm(vmUid, mips);
                        getPeMap().get(vmUid).add(pe);
                        availableMips -= mips;
                        break;
                    } else {
                        peProvisioner.allocateMipsForVm(vmUid, availableMips);
                        getPeMap().get(vmUid).add(pe);
                        mips -= availableMips;
                        if (mips <= 0.1) {
                            break;
                        }
                        if (!peIterator.hasNext()) {
                            Log.printLine("There is no enough MIPS (" + mips + ") to accommodate VM " + vmUid);
                        }
                        pe = peIterator.next();
                        peProvisioner = pe.getPeProvisioner();
                        availableMips = peProvisioner.getAvailableMips();
                    }
                }
            }
        }
    }

    @Override
    public void deallocatePesForVm(Vm vm) {
        getMipsMapRequested().remove(vm.getUid());
        setPesInUse(0);
        getMipsMap().clear();
        setAvailableMips(PeList.getTotalMips(getPeList()));

        for (Pe pe : getPeList()) {
            pe.getPeProvisioner().deallocateMipsForVm(vm);
        }

        for (Map.Entry<String, List<Double>> entry : getMipsMapRequested().entrySet()) {
            allocatePesForVm(entry.getKey(), entry.getValue());
        }

        updatePeProvisioning();
    }

    @Override
    public void deallocatePesForAllVms() {
        super.deallocatePesForAllVms();
        getMipsMapRequested().clear();
        setPesInUse(0);
    }

    @Override
    public double getMaxAvailableMips() {
        return getAvailableMips();
    }

    protected void setPesInUse(int pesInUse) {
        this.pesInUse = pesInUse;
    }

    protected int getPesInUse() {
        return pesInUse;
    }

    protected Map<String, List<Double>> getMipsMapRequested() {
        return mipsMapRequested;
    }

    protected void setMipsMapRequested(Map<String, List<Double>> mipsMapRequested) {
        this.mipsMapRequested = mipsMapRequested;
    }
}
