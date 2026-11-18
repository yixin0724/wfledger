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

public class ClusteringSizeEstimator {

    protected static double f(double k, double t, double s, double theta, double phi_gamma, double phi_ts) {
        double d = (k * t + s) * (phi_ts - 1);
        return d / k * Math.exp(Math.pow(d / theta, phi_gamma));
    }

    protected static double fprime(double k, double t, double s, double theta, double phi) {
        double first_part = Math.exp(Math.pow((k * t + s) / theta, phi));
        double second_part = t * phi / k * Math.pow((k * t + s) / theta, phi) - s / (k * k);
        return first_part * second_part;
    }

    public static int estimateK(double t, double s, double theta, double phi_gamma, double phi_ts) {
        int optimalK = 0;
        double minM = Double.MAX_VALUE;
        for (int k = 1; k < 200; k++) {
            double M = f(k, t, s, theta, phi_gamma, phi_ts);
            if (M < minM) {
                minM = M;
                optimalK = k;
            }
        }
        return optimalK;
    }
}
