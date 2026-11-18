/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

public final class DataCloudTags {

    private static final int BASE = 400;

    private static final int RM_BASE = 500;

    private static final int CTLG_BASE = 600;

    public static final int DEFAULT_MTU = 1500;

    public static final int PKT_SIZE = DEFAULT_MTU * 100;

    public static final int DEFAULT_STORAGE_SIZE = 10000000;

    public static final int REGISTER_REPLICA_CTLG = BASE + 1;

    public static final int INQUIRY_LOCAL_RC_LIST = BASE + 2;

    public static final int INQUIRY_GLOBAL_RC_LIST = BASE + 3;

    public static final int INQUIRY_RC_LIST = BASE + 4;

    public static final int INQUIRY_RC_RESULT = BASE + 5;

    public static final int DATAcloudlet_SUBMIT = BASE + 6;

    public static final int FILE_REQUEST = RM_BASE + 1;

    public static final int FILE_DELIVERY = RM_BASE + 2;

    public static final int FILE_ADD_MASTER = RM_BASE + 10;

    public static final int FILE_ADD_MASTER_RESULT = RM_BASE + 11;

    public static final int FILE_ADD_REPLICA = RM_BASE + 12;

    public static final int FILE_ADD_REPLICA_RESULT = RM_BASE + 13;

    public static final int FILE_ADD_SUCCESSFUL = RM_BASE + 20;

    public static final int FILE_ADD_ERROR_STORAGE_FULL = RM_BASE + 21;

    public static final int FILE_ADD_ERROR_EMPTY = RM_BASE + 22;

    public static final int FILE_ADD_ERROR_EXIST_READ_ONLY = RM_BASE + 23;

    public static final int FILE_ADD_ERROR = RM_BASE + 24;

    public static final int FILE_ADD_ERROR_ACCESS_DENIED = RM_BASE + 25;

    public static final int FILE_DELETE_MASTER = RM_BASE + 30;

    public static final int FILE_DELETE_MASTER_RESULT = RM_BASE + 31;

    public static final int FILE_DELETE_REPLICA = RM_BASE + 32;

    public static final int FILE_DELETE_REPLICA_RESULT = RM_BASE + 33;

    public static final int FILE_DELETE_SUCCESSFUL = RM_BASE + 40;

    public static final int FILE_DELETE_ERROR = RM_BASE + 41;

    public static final int FILE_DELETE_ERROR_READ_ONLY = RM_BASE + 42;

    public static final int FILE_DELETE_ERROR_DOESNT_EXIST = RM_BASE + 43;

    public static final int FILE_DELETE_ERROR_IN_USE = RM_BASE + 44;

    public static final int FILE_DELETE_ERROR_ACCESS_DENIED = RM_BASE + 45;

    public static final int FILE_MODIFY = RM_BASE + 50;

    public static final int FILE_MODIFY_RESULT = RM_BASE + 51;

    public static final int FILE_MODIFY_SUCCESSFUL = RM_BASE + 60;

    public static final int FILE_MODIFY_ERROR = RM_BASE + 61;

    public static final int FILE_MODIFY_ERROR_READ_ONLY = RM_BASE + 62;

    public static final int FILE_MODIFY_ERROR_DOESNT_EXIST = RM_BASE + 63;

    public static final int FILE_MODIFY_ERROR_IN_USE = RM_BASE + 64;

    public static final int FILE_MODIFY_ERROR_ACCESS_DENIED = RM_BASE + 65;

    public static final int CTLG_GET_REPLICA = CTLG_BASE + 1;

    public static final int CTLG_REPLICA_DELIVERY = CTLG_BASE + 2;

    public static final int CTLG_GET_REPLICA_LIST = CTLG_BASE + 3;

    public static final int CTLG_REPLICA_LIST_DELIVERY = CTLG_BASE + 4;

    public static final int CTLG_GET_FILE_ATTR = CTLG_BASE + 5;

    public static final int CTLG_FILE_ATTR_DELIVERY = CTLG_BASE + 6;

    public static final int CTLG_FILTER = CTLG_BASE + 7;

    public static final int CTLG_FILTER_DELIVERY = CTLG_BASE + 8;

    public static final int CTLG_ADD_MASTER = CTLG_BASE + 10;

    public static final int CTLG_ADD_MASTER_RESULT = CTLG_BASE + 11;

    public static final int CTLG_ADD_MASTER_SUCCESSFUL = CTLG_BASE + 12;

    public static final int CTLG_ADD_MASTER_ERROR = CTLG_BASE + 13;

    public static final int CTLG_ADD_MASTER_ERROR_FULL = CTLG_BASE + 14;

    public static final int CTLG_DELETE_MASTER = CTLG_BASE + 20;

    public static final int CTLG_DELETE_MASTER_RESULT = CTLG_BASE + 21;

    public static final int CTLG_DELETE_MASTER_SUCCESSFUL = CTLG_BASE + 22;

    public static final int CTLG_DELETE_MASTER_ERROR = CTLG_BASE + 23;

    public static final int CTLG_DELETE_MASTER_DOESNT_EXIST = CTLG_BASE + 24;

    public static final int CTLG_DELETE_MASTER_REPLICAS_EXIST = CTLG_BASE + 25;

    public static final int CTLG_ADD_REPLICA = CTLG_BASE + 30;

    public static final int CTLG_ADD_REPLICA_RESULT = CTLG_BASE + 31;

    public static final int CTLG_ADD_REPLICA_SUCCESSFUL = CTLG_BASE + 32;

    public static final int CTLG_ADD_REPLICA_ERROR = CTLG_BASE + 33;

    public static final int CTLG_ADD_REPLICA_ERROR_DOESNT_EXIST = CTLG_BASE + 34;

    public static final int CTLG_ADD_REPLICA_ERROR_FULL = CTLG_BASE + 35;

    public static final int CTLG_DELETE_REPLICA = CTLG_BASE + 40;

    public static final int CTLG_DELETE_REPLICA_RESULT = CTLG_BASE + 41;

    public static final int CTLG_DELETE_REPLICA_SUCCESSFUL = CTLG_BASE + 42;

    public static final int CTLG_DELETE_REPLICA_ERROR = CTLG_BASE + 43;

    public static final int CTLG_DELETE_REPLICA_ERROR_DOESNT_EXIST = CTLG_BASE + 44;

    public static final int CTLG_MODIFY_MASTER = CTLG_BASE + 50;

    public static final int CTLG_MODIFY_MASTER_RESULT = CTLG_BASE + 51;

    public static final int CTLG_MODIFY_MASTER_SUCCESSFUL = CTLG_BASE + 52;

    public static final int CTLG_MODIFY_MASTER_ERROR = CTLG_BASE + 53;

    public static final int CTLG_MODIFY_MASTER_ERROR_DOESNT_EXIST = CTLG_BASE + 54;

    public static final int CTLG_MODIFY_MASTER_ERROR_READ_ONLY = CTLG_BASE + 55;

    private DataCloudTags() {
        throw new UnsupportedOperationException("DataCloudTags cannot be instantiated");
    }
}
