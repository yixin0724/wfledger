/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.util.List;

public interface Storage {

    String getName();

    double getCapacity();

    double getCurrentSize();

    double getMaxTransferRate();

    double getAvailableSpace();

    boolean setMaxTransferRate(int rate);

    boolean isFull();

    int getNumStoredFile();

    boolean reserveSpace(int fileSize);

    double addReservedFile(File file);

    boolean hasPotentialAvailableSpace(int fileSize);

    File getFile(String fileName);

    List<String> getFileNameList();

    double addFile(File file);

    double addFile(List<File> list);

    File deleteFile(String fileName);

    double deleteFile(String fileName, File file);

    double deleteFile(File file);

    boolean contains(String fileName);

    boolean contains(File file);

    boolean renameFile(File file, String newName);
}
