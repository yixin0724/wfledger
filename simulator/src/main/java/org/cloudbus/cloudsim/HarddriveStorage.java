/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.cloudbus.cloudsim.distributions.ContinuousDistribution;

public class HarddriveStorage implements Storage {

    private List<String> nameList;

    private List<File> fileList;

    private final String name;

    private ContinuousDistribution gen;

    private double currentSize;

    private final double capacity;

    private double maxTransferRate;

    private double latency;

    private double avgSeekTime;

    public HarddriveStorage(String name, double capacity) throws ParameterException {
        if (name == null || name.length() == 0) {
            throw new ParameterException("HarddriveStorage(): Error - invalid storage name.");
        }

        if (capacity <= 0) {
            throw new ParameterException("HarddriveStorage(): Error - capacity <= 0.");
        }

        this.name = name;
        this.capacity = capacity;
        init();
    }

    public HarddriveStorage(double capacity) throws ParameterException {
        if (capacity <= 0) {
            throw new ParameterException("HarddriveStorage(): Error - capacity <= 0.");
        }
        name = "HarddriveStorage";
        this.capacity = capacity;
        init();
    }

    private void init() {
        fileList = new ArrayList<File>();
        nameList = new ArrayList<String>();
        gen = null;
        currentSize = 0;

        latency = 0.00417;
        avgSeekTime = 0.009;
        maxTransferRate = 133;
    }

    @Override
    public double getAvailableSpace() {
        return capacity - currentSize;
    }

    @Override
    public boolean isFull() {
        if (Math.abs(currentSize - capacity) < .0000001) {
            return true;
        }
        return false;
    }

    @Override
    public int getNumStoredFile() {
        return fileList.size();
    }

    @Override
    public boolean reserveSpace(int fileSize) {
        if (fileSize <= 0) {
            return false;
        }

        if (currentSize + fileSize >= capacity) {
            return false;
        }

        currentSize += fileSize;
        return true;
    }

    @Override
    public double addReservedFile(File file) {
        if (file == null) {
            return 0;
        }

        currentSize -= file.getSize();
        double result = addFile(file);

        if (result == 0.0) {
            currentSize += file.getSize();
        }

        return result;
    }

    @Override
    public boolean hasPotentialAvailableSpace(int fileSize) {
        if (fileSize <= 0) {
            return false;
        }

        if (getAvailableSpace() > fileSize) {
            return true;
        }

        Iterator<File> it = fileList.iterator();
        File file = null;
        int deletedFileSize = 0;

        boolean result = false;
        while (it.hasNext()) {
            file = it.next();
            if (!file.isReadOnly()) {
                deletedFileSize += file.getSize();
            }

            if (deletedFileSize > fileSize) {
                result = true;
                break;
            }
        }

        return result;
    }

    @Override
    public double getCapacity() {
        return capacity;
    }

    @Override
    public double getCurrentSize() {
        return currentSize;
    }

    @Override
    public String getName() {
        return name;
    }

    public boolean setLatency(double latency) {
        if (latency < 0) {
            return false;
        }

        this.latency = latency;
        return true;
    }

    public double getLatency() {
        return latency;
    }

    @Override
    public boolean setMaxTransferRate(int rate) {
        if (rate <= 0) {
            return false;
        }

        maxTransferRate = rate;
        return true;
    }

    /**
     * Sets a fractional maximum transfer rate.
     *
     * <p>WfLedger STD-V1 (2026-11-25): the platform's SHARED storage rate is a finite positive double so the
     * standard study can calibrate a target communication-to-computation ratio; the backing field was already a
     * double. Unlike the legacy int setter, an invalid rate fails fast instead of returning {@code false}.
     *
     * @param rate finite positive rate in decimal MB/s
     */
    public void setMaxTransferRate(double rate) {
        if (!(rate > 0.0) || Double.isInfinite(rate)) {
            throw new IllegalArgumentException("Storage transfer rate must be finite and positive: " + rate);
        }
        maxTransferRate = rate;
    }

    @Override
    public double getMaxTransferRate() {
        return maxTransferRate;
    }

    public boolean setAvgSeekTime(double seekTime) {
        return setAvgSeekTime(seekTime, null);
    }

    public boolean setAvgSeekTime(double seekTime, ContinuousDistribution gen) {
        if (seekTime <= 0.0) {
            return false;
        }

        avgSeekTime = seekTime;
        this.gen = gen;
        return true;
    }

    public double getAvgSeekTime() {
        return avgSeekTime;
    }

    @Override
    public File getFile(String fileName) {

        File obj = null;
        if (fileName == null || fileName.length() == 0) {
            Log.printLine(name + ".getFile(): Warning - invalid " + "file name.");
            return obj;
        }

        Iterator<File> it = fileList.iterator();
        int size = 0;
        int index = 0;
        boolean found = false;
        File tempFile = null;

        while (it.hasNext()) {
            tempFile = it.next();
            size += tempFile.getSize();
            if (tempFile.getName().equals(fileName)) {
                found = true;
                obj = tempFile;
                break;
            }

            index++;
        }

        if (found) {
            obj = fileList.get(index);
            double seekTime = getSeekTime(size);
            double transferTime = getTransferTime(obj.getSize());

            obj.setTransactionTime(seekTime + transferTime);
        }

        return obj;
    }

    @Override
    public List<String> getFileNameList() {
        return nameList;
    }

    private double getSeekTime(int fileSize) {
        double result = 0;

        if (gen != null) {
            result += gen.sample();
        }

        if (fileSize > 0 && capacity != 0) {
            result += (fileSize / capacity);
        }

        return result;
    }

    private double getTransferTime(int fileSize) {
        double result = 0;
        if (fileSize > 0 && capacity != 0) {
            result = (fileSize * maxTransferRate) / capacity;
        }

        return result;
    }

    private boolean isFileValid(File file, String methodName) {

        if (file == null) {
            Log.printLine(name + "." + methodName + ": Warning - the given file is null.");
            return false;
        }

        String fileName = file.getName();
        if (fileName == null || fileName.length() == 0) {
            Log.printLine(name + "." + methodName + ": Warning - invalid file name.");
            return false;
        }

        return true;
    }

    @Override
    public double addFile(File file) {
        double result = 0.0;

        if (!isFileValid(file, "addFile()")) {
            return result;
        }

        if (file.getSize() + currentSize > capacity) {
            Log.printLine(name + ".addFile(): Warning - not enough space" + " to store " + file.getName());
            return result;
        }

        if (!contains(file.getName())) {
            double seekTime = getSeekTime(file.getSize());
            double transferTime = getTransferTime(file.getSize());

            fileList.add(file);
            nameList.add(file.getName());
            currentSize += file.getSize();
            result = seekTime + transferTime;
        }
        file.setTransactionTime(result);
        return result;
    }

    @Override
    public double addFile(List<File> list) {
        double result = 0.0;
        if (list == null || list.isEmpty()) {
            Log.printLine(name + ".addFile(): Warning - list is empty.");
            return result;
        }

        Iterator<File> it = list.iterator();
        File file = null;
        while (it.hasNext()) {
            file = it.next();
            result += addFile(file);
        }
        return result;
    }

    @Override
    public File deleteFile(String fileName) {
        if (fileName == null || fileName.length() == 0) {
            return null;
        }

        Iterator<File> it = fileList.iterator();
        File file = null;
        while (it.hasNext()) {
            file = it.next();
            String name = file.getName();

            if (fileName.equals(name)) {
                double result = deleteFile(file);
                file.setTransactionTime(result);
                break;
            } else {
                file = null;
            }
        }
        return file;
    }

    @Override
    public double deleteFile(String fileName, File file) {
        return deleteFile(file);
    }

    @Override
    public double deleteFile(File file) {
        double result = 0.0;

        if (!isFileValid(file, "deleteFile()")) {
            return result;
        }
        double seekTime = getSeekTime(file.getSize());
        double transferTime = getTransferTime(file.getSize());

        if (contains(file)) {
            fileList.remove(file);
            nameList.remove(file.getName());
            currentSize -= file.getSize();
            result = seekTime + transferTime;
            file.setTransactionTime(result);
        }
        return result;
    }

    @Override
    public boolean contains(String fileName) {
        boolean result = false;
        if (fileName == null || fileName.length() == 0) {
            Log.printLine(name + ".contains(): Warning - invalid file name");
            return result;
        }

        Iterator<String> it = nameList.iterator();
        while (it.hasNext()) {
            String name = it.next();
            if (name.equals(fileName)) {
                result = true;
                break;
            }
        }
        return result;
    }

    @Override
    public boolean contains(File file) {
        boolean result = false;
        if (!isFileValid(file, "contains()")) {
            return result;
        }

        result = contains(file.getName());
        return result;
    }

    @Override
    public boolean renameFile(File file, String newName) {

        boolean result = false;
        if (contains(newName)) {
            return result;
        }

        File obj = getFile(file.getName());
        if (obj == null) {
            return result;
        } else {
            obj.setName(newName);
        }

        Iterator<String> it = nameList.iterator();
        while (it.hasNext()) {
            String name = it.next();
            if (name.equals(file.getName())) {
                file.setTransactionTime(0);
                nameList.remove(name);
                nameList.add(newName);
                result = true;
                break;
            }
        }

        return result;
    }
}
