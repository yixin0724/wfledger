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
package org.workflowsim.reclustering;

/**
 * Estimates the task reclustering size given the abstract runtime, overhead, and
 * failure distribution parameters.
 *
 * <p>This estimator uses the legacy analytic approximation and serves as part of a
 * compatibility recovery strategy; its parameters do not come automatically from
 * calibration against a real platform.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Mar 11, 2014
 */
public class ClusteringSizeEstimator {

    /**
     * Computes the objective function under the approximation {@code n / k >> r}.
     *
     * @param k clustering size
     * @param t task runtime
     * @param s system overhead
     * @param theta arrival interval estimation parameter
     * @param phi_gamma distribution shape parameter
     * @param phi_ts time scaling parameter
     * @return approximate objective function value
     */
    protected static double f(double k, double t, double s, double theta, double phi_gamma, double phi_ts) {
        double d = (k * t + s) * (phi_ts - 1);
        return d / k * Math.exp(Math.pow(d / theta, phi_gamma));
    }

    /**
     * Computes the first derivative of the objective function under the approximation
     * {@code n / k >> r}.
     *
     * @param k clustering size
     * @param t task runtime
     * @param s system overhead
     * @param theta arrival interval estimation parameter
     * @param phi Weibull distribution parameter
     * @return approximate first derivative of the objective function
     */
    protected static double fprime(double k, double t, double s, double theta, double phi) {
        double first_part = Math.exp(Math.pow((k * t + s) / theta, phi));
        double second_part = t * phi / k * Math.pow((k * t + s) / theta, phi) - s / (k * k);
        return first_part * second_part;
    }

    /**
     * Enumerates candidate clustering sizes under the approximation {@code n / k >> r}.
     *
     * @param t task runtime
     * @param s system overhead
     * @param theta arrival interval estimation parameter
     * @param phi_gamma distribution shape parameter
     * @param phi_ts time scaling parameter
     * @return clustering size with the smallest objective value in the enumerated range
     */
    public static int estimateK(double t, double s, double theta, double phi_gamma, double phi_ts) {
        int optimalK = 0;
        double minM = Double.MAX_VALUE;
        for (int k = 1; k < 200; k++) {
            double M = f(k, t, s, theta, phi_gamma, phi_ts);
            if (M < minM) {
                minM = M;
                optimalK = k;
            }
            // The objective value of each candidate k can be printed for debugging.
        }
        return optimalK;
    }
}
