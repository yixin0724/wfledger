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

/**
 * Two-level signal that varies periodically.
 *
 * <p>Within one period the signal holds the upper or the lower bound for {@code portion} of the
 * period, then switches to the other value. The class can serve as abstract input for periodic
 * fault or overhead experiments; it does not model a continuous-time physical process.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2014
 */
public class PeriodicalSignal {

    /** Signal period in simulated seconds. */
    private double period;
    /** Upper bound of the signal. */
    private double upperbound;
    /** Lower bound of the signal. */
    private double lowerbound;
    /** Fraction of one period occupied by the signal state that occurs first. */
    private double portion;
    /** When {@code true} the period starts at the upper bound; otherwise at the lower bound. */
    private boolean direction;

    /**
     * Create a periodic two-level signal.
     *
     * @param period period in simulated seconds
     * @param upperbound upper bound of the signal
     * @param lowerbound lower bound of the signal
     * @param portion fraction of one period occupied by the starting state
     * @param direction {@code true} when the starting state is the upper bound
     */
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

    /**
     * Create a periodic two-level signal whose starting state is the upper bound.
     *
     * @param period period in simulated seconds
     * @param upperbound upper bound of the signal
     * @param lowerbound lower bound of the signal
     * @param portion fraction of one period occupied by the starting state
     */
    public PeriodicalSignal(double period, double upperbound, double lowerbound, double portion) {
        this(period, upperbound, lowerbound, portion, true);
    }

    /**
     * Create a signal that starts at the upper bound with each of the two states holding half a
     * period.
     *
     * @param period period in simulated seconds
     * @param upperbound upper bound of the signal
     * @param lowerbound lower bound of the signal
     */
    public PeriodicalSignal(double period, double upperbound, double lowerbound) {
        this(period, upperbound, lowerbound, 0.5);
    }

    /**
     * Return the signal value at the specified simulation time.
     *
     * @param currentTime simulation time
     * @return the upper or the lower bound; a negative time returns {@code 0.0}
     */
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

    /** @return upper bound of the signal */
    public double getUpperBound() {
        return upperbound;
    }

    /** @return lower bound of the signal */
    public double getLowerBound() {
        return lowerbound;
    }

    /** @return period in simulated seconds */
    public double getPeriod() {
        return period;
    }

    /** @return fraction of one period occupied by the starting state */
    public double getPortion() {
        return portion;
    }

    /** @return {@code true} when the period starts at the upper bound */
    public boolean getDirection() {
        return direction;
    }

    private static void validatePositiveFinite(String parameter, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(parameter + " must be finite and greater than zero");
        }
    }
}
