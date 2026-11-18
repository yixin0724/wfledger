/*
 * Gokul Poduval & Chen-Khong Tham
 * Computer Communication Networks (CCN) Lab
 * Dept of Electrical & Computer Engineering
 * National University of Singapore
 * August 2004
 *
 * Licence: GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2004, The University of Melbourne, Australia and National
 * University of Singapore
 * Packet.java - Interface of a Network Packet.
 *
 */

package org.cloudbus.cloudsim;

public interface Packet {

    @Override
    String toString();

    long getSize();

    boolean setSize(long size);

    int getDestId();

    int getId();

    int getSrcId();

    int getNetServiceType();

    void setNetServiceType(int serviceType);

    int getLast();

    void setLast(int last);

    int getTag();
}
