/*
 * ** Network and Service Differentiation Extensions to CloudSim 3.0 **
 *
 * Gokul Poduval & Chen-Khong Tham
 * Computer Communication Networks (CCN) Lab
 * Dept of Electrical & Computer Engineering
 * National University of Singapore
 * October 2004
 *
 * Licence: GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2004, The University of Melbourne, Australia and
 * National University of Singapore
 * ParameterException.java - Thrown for illegal parameters
 *
 */

package org.cloudbus.cloudsim;

public class ParameterException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String message;

    public ParameterException() {
        super();
        message = null;
    }

    public ParameterException(String message) {
        super();
        this.message = message;
    }

    @Override
    public String toString() {
        return message;
    }
}
