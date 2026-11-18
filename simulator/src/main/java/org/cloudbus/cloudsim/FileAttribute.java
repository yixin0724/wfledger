/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.util.Date;

import org.cloudbus.cloudsim.core.CloudSim;

public class FileAttribute {

    private String name;

    private String ownerName;

    private int id;

    private int type;

    private int size;

    private int checksum;

    private double lastUpdateTime;

    private long creationTime;

    private double cost;

    private boolean masterCopy;

    private boolean readOnly;

    private int resourceId;

    public FileAttribute(String fileName, int fileSize) throws ParameterException {

        if (fileName == null || fileName.length() == 0) {
            throw new ParameterException("FileAttribute(): Error - invalid file name.");
        }

        if (fileSize <= 0) {
            throw new ParameterException("FileAttribute(): Error - size <= 0.");
        }

        size = fileSize;
        name = fileName;

        Date date = CloudSim.getSimulationCalendar().getTime();
        if (date == null) {
            creationTime = 0;
        } else {
            creationTime = date.getTime();
        }

        ownerName = null;
        id = File.NOT_REGISTERED;
        checksum = 0;
        type = File.TYPE_UNKOWN;
        lastUpdateTime = 0;
        cost = 0;
        resourceId = -1;
        masterCopy = true;
        readOnly = false;
    }

    public boolean copyValue(FileAttribute attr) {
        if (attr == null) {
            return false;
        }

        attr.setFileSize(size);
        attr.setResourceID(resourceId);
        attr.setOwnerName(ownerName);
        attr.setUpdateTime(lastUpdateTime);
        attr.setRegistrationId(id);
        attr.setType(type);
        attr.setChecksum(checksum);
        attr.setCost(cost);
        attr.setMasterCopy(masterCopy);
        attr.setReadOnly(readOnly);
        attr.setName(name);
        attr.setCreationTime(creationTime);

        return true;
    }

    public boolean setCreationTime(long creationTime) {
        if (creationTime <= 0) {
            return false;
        }

        this.creationTime = creationTime;
        return true;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public boolean setResourceID(int resourceID) {
        if (resourceID == -1) {
            return false;
        }

        resourceId = resourceID;
        return true;
    }

    public int getResourceID() {
        return resourceId;
    }

    public boolean setOwnerName(String name) {
        if (name == null || name.length() == 0) {
            return false;
        }

        ownerName = name;
        return true;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public int getAttributeSize() {
        int length = DataCloudTags.PKT_SIZE;
        if (ownerName != null) {
            length += ownerName.length();
        }

        if (name != null) {
            length += name.length();
        }

        return length;
    }

    public boolean setFileSize(int fileSize) {
        if (fileSize < 0) {
            return false;
        }

        size = fileSize;
        return true;
    }

    public int getFileSize() {
        return size;
    }

    public int getFileSizeInByte() {
        return size * Consts.MILLION;
    }

    public boolean setUpdateTime(double time) {
        if (time <= 0 || time < lastUpdateTime) {
            return false;
        }

        lastUpdateTime = time;
        return true;
    }

    public double getLastUpdateTime() {
        return lastUpdateTime;
    }

    public boolean setRegistrationId(int id) {
        if (id < 0) {
            return false;
        }

        this.id = id;
        return true;
    }

    public int getRegistrationID() {
        return id;
    }

    public boolean setType(int type) {
        if (type < 0) {
            return false;
        }

        this.type = type;
        return true;
    }

    public int getType() {
        return type;
    }

    public boolean setChecksum(int checksum) {
        if (checksum < 0) {
            return false;
        }

        this.checksum = checksum;
        return true;
    }

    public int getChecksum() {
        return checksum;
    }

    public boolean setCost(double cost) {
        if (cost < 0) {
            return false;
        }

        this.cost = cost;
        return true;
    }

    public double getCost() {
        return cost;
    }

    public boolean isRegistered() {
        boolean result = true;
        if (id == File.NOT_REGISTERED) {
            result = false;
        }

        return result;
    }

    public void setMasterCopy(boolean masterCopy) {
        this.masterCopy = masterCopy;
    }

    public boolean isMasterCopy() {
        return masterCopy;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
