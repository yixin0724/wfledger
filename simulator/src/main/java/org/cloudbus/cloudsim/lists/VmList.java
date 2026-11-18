/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.lists;

import java.util.List;

import org.cloudbus.cloudsim.Vm;

public class VmList {

    public static <T extends Vm> T getById(List<T> vmList, int id) {
        for (T vm : vmList) {
            if (vm.getId() == id) {
                return vm;
            }
        }
        return null;
    }

    public static <T extends Vm> T getByIdAndUserId(List<T> vmList, int id, int userId) {
        for (T vm : vmList) {
            if (vm.getId() == id && vm.getUserId() == userId) {
                return vm;
            }
        }
        return null;
    }
}
