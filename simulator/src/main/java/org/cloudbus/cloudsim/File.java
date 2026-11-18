/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

public class File {

    private String name;

    private FileAttribute attribute;

    private double transactionTime;

    public static final int NOT_REGISTERED = -1;

    public static final int TYPE_UNKOWN = 0;

    public static final int TYPE_RAW_DATA = 1;

    public static final int TYPE_RECONSTRUCTED_DATA = 2;

    public static final int TYPE_TAG_DATA = 3;

    public File(String fileName, int fileSize) throws ParameterException {
        if (fileName == null || fileName.length() == 0) {
            throw new ParameterException("File(): Error - invalid file name.");
        }

        if (fileSize <= 0) {
            throw new ParameterException("File(): Error - size <= 0.");
        }

        name = fileName;
        attribute = new FileAttribute(fileName, fileSize);
        transactionTime = 0;
    }

    public File(File file) throws ParameterException {
        if (file == null) {
            throw new ParameterException("File(): Error - file is null.");
        }

        FileAttribute fileAttr = file.getFileAttribute();
        attribute.copyValue(fileAttr);
        fileAttr.setMasterCopy(false);
    }

    public File makeReplica() {
        return makeCopy();
    }

    public File makeMasterCopy() {
        File file = makeCopy();
        if (file != null) {
            file.setMasterCopy(true);
        }

        return file;
    }

    private File makeCopy() {
        File file = null;
        try {
            file = new File(name, attribute.getFileSize());
            FileAttribute fileAttr = file.getFileAttribute();
            attribute.copyValue(fileAttr);
            fileAttr.setMasterCopy(false);
        } catch (Exception e) {
            file = null;
        }

        return file;
    }

    public FileAttribute getFileAttribute() {
        return attribute;
    }

    public int getAttributeSize() {
        return attribute.getAttributeSize();
    }

    public boolean setResourceID(int resourceID) {
        return attribute.setResourceID(resourceID);
    }

    public int getResourceID() {
        return attribute.getResourceID();
    }

    public String getName() {
        return attribute.getName();
    }

    public void setName(String name) {
        attribute.setName(name);
    }

    public boolean setOwnerName(String name) {
        return attribute.setOwnerName(name);
    }

    public String getOwnerName() {
        return attribute.getOwnerName();
    }

    public int getSize() {
        return attribute.getFileSize();
    }

    public int getSizeInByte() {
        return attribute.getFileSizeInByte();
    }

    public boolean setFileSize(int fileSize) {
        return attribute.setFileSize(fileSize);
    }

    public boolean setUpdateTime(double time) {
        return attribute.setUpdateTime(time);
    }

    public double getLastUpdateTime() {
        return attribute.getLastUpdateTime();
    }

    public boolean setRegistrationID(int id) {
        return attribute.setRegistrationId(id);
    }

    public int getRegistrationID() {
        return attribute.getRegistrationID();
    }

    public boolean setType(int type) {
        return attribute.setType(type);
    }

    public int getType() {
        return attribute.getType();
    }

    public boolean setChecksum(int checksum) {
        return attribute.setChecksum(checksum);
    }

    public int getChecksum() {
        return attribute.getChecksum();
    }

    public boolean setCost(double cost) {
        return attribute.setCost(cost);
    }

    public double getCost() {
        return attribute.getCost();
    }

    public long getCreationTime() {
        return attribute.getCreationTime();
    }

    public boolean isRegistered() {
        return attribute.isRegistered();
    }

    public void setMasterCopy(boolean masterCopy) {
        attribute.setMasterCopy(masterCopy);
    }

    public boolean isMasterCopy() {
        return attribute.isMasterCopy();
    }

    public void setReadOnly(boolean readOnly) {
        attribute.setReadOnly(readOnly);
    }

    public boolean isReadOnly() {
        return attribute.isReadOnly();
    }

    public boolean setTransactionTime(double time) {
        if (time < 0) {
            return false;
        }

        transactionTime = time;
        return true;
    }

    public double getTransactionTime() {
        return transactionTime;
    }
}
