/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.core;

public class CloudSimTags {

    private static final int BASE = 0;

    private static final int NETBASE = 100;

    public static final int TRUE = 1;

    public static final int FALSE = 0;

    public static final int DEFAULT_BAUD_RATE = 9600;

    public static final double SCHEDULE_NOW = 0.0;

    public static final int END_OF_SIMULATION = -1;

    public static final int ABRUPT_END_OF_SIMULATION = -2;

    public static final int INSIGNIFICANT = BASE + 0;

    public static final int EXPERIMENT = BASE + 1;

    public static final int REGISTER_RESOURCE = BASE + 2;

    public static final int REGISTER_RESOURCE_AR = BASE + 3;

    public static final int RESOURCE_LIST = BASE + 4;

    public static final int RESOURCE_AR_LIST = BASE + 5;

    public static final int RESOURCE_CHARACTERISTICS = BASE + 6;

    public static final int RESOURCE_DYNAMICS = BASE + 7;

    public static final int RESOURCE_NUM_PE = BASE + 8;

    public static final int RESOURCE_NUM_FREE_PE = BASE + 9;

    public static final int RECORD_STATISTICS = BASE + 10;

    public static final int RETURN_STAT_LIST = BASE + 11;

    public static final int RETURN_ACC_STATISTICS_BY_CATEGORY = BASE + 12;

    public static final int REGISTER_REGIONAL_GIS = BASE + 13;

    public static final int REQUEST_REGIONAL_GIS = BASE + 14;

    public static final int RESOURCE_CHARACTERISTICS_REQUEST = BASE + 15;

    public static final int INFOPKT_SUBMIT = NETBASE + 5;

    public static final int INFOPKT_RETURN = NETBASE + 6;

    public static final int CLOUDLET_RETURN = BASE + 20;

    public static final int CLOUDLET_SUBMIT = BASE + 21;

    public static final int CLOUDLET_SUBMIT_ACK = BASE + 22;

    public static final int CLOUDLET_CANCEL = BASE + 23;

    public static final int CLOUDLET_STATUS = BASE + 24;

    public static final int CLOUDLET_PAUSE = BASE + 25;

    public static final int CLOUDLET_PAUSE_ACK = BASE + 26;

    public static final int CLOUDLET_RESUME = BASE + 27;

    public static final int CLOUDLET_RESUME_ACK = BASE + 28;

    public static final int CLOUDLET_MOVE = BASE + 29;

    public static final int CLOUDLET_MOVE_ACK = BASE + 30;

    public static final int VM_CREATE = BASE + 31;

    public static final int VM_CREATE_ACK = BASE + 32;

    public static final int VM_DESTROY = BASE + 33;

    public static final int VM_DESTROY_ACK = BASE + 34;

    public static final int VM_MIGRATE = BASE + 35;

    public static final int VM_MIGRATE_ACK = BASE + 36;

    public static final int VM_DATA_ADD = BASE + 37;

    public static final int VM_DATA_ADD_ACK = BASE + 38;

    public static final int VM_DATA_DEL = BASE + 39;

    public static final int VM_DATA_DEL_ACK = BASE + 40;

    public static final int VM_DATACENTER_EVENT = BASE + 41;

    public static final int VM_BROKER_EVENT = BASE + 42;

    public static final int Network_Event_UP = BASE + 43;

    public static final int Network_Event_send = BASE + 44;

    public static final int RESOURCE_Register = BASE + 45;

    public static final int Network_Event_DOWN = BASE + 46;

    public static final int Network_Event_Host = BASE + 47;

    public static final int NextCycle = BASE + 48;

    private CloudSimTags() {
        throw new UnsupportedOperationException("CloudSim Tags cannot be instantiated");
    }
}
