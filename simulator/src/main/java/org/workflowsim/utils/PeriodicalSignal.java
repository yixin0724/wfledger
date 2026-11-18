/*
 *
 *   Copyright 2013-2014 University Of Southern California
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.workflowsim.utils;

public class PeriodicalSignal {

    private double period;

    private double upperbound;

    private double lowerbound;

    private double portion;

    private boolean direction;

    public PeriodicalSignal(double period, double upperbound, double lowerbound, double portion, boolean direction) {
        validatePositiveFinite("Period", period);
        validatePositiveFinite("Upper bound", upperbound);
        validatePositiveFinite("Lower bound", lowerbound);
        if (Double.isNaN(portion) || Double.isInfinite(portion) || portion < 0.0 || portion > 1.0) {
            throw new IllegalArgumentException("Period portion must be finite and between zero and one");
        }
        this.lowerbound = lowerbound;
        this.upperbound = upperbound;
        this.period = period;
        this.portion = portion;
        this.direction = direction;
    }

    public PeriodicalSignal(double period, double upperbound, double lowerbound, double portion) {
        this(period, upperbound, lowerbound, portion, true);
    }

    public PeriodicalSignal(double period, double upperbound, double lowerbound) {
        this(period, upperbound, lowerbound, 0.5);
    }

    public double getCurrentSignal(double currentTime) {
        if (currentTime < 0.0) {
            return 0.0;
        }
        currentTime = currentTime % period;
        if (currentTime <= period * portion) {
            if (direction) {
                return upperbound;
            } else {
                return lowerbound;
            }
        } else {
            if (direction) {
                return lowerbound;
            } else {
                return upperbound;
            }
        }
    }

    public double getUpperBound() {
        return upperbound;
    }

    public double getLowerBound() {
        return lowerbound;
    }

    public double getPeriod() {
        return period;
    }

    public double getPortion() {
        return portion;
    }

    public boolean getDirection() {
        return direction;
    }

    private static void validatePositiveFinite(String parameter, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(parameter + " must be finite and greater than zero");
        }
    }
}
