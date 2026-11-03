/**
 * Copyright 2012-2013University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim.utils;

/**
 * Parameters required by task clustering.
 *
 * <p>{@code clustersNum} and {@code clustersSize} are interpreted by the concrete
 * clustering strategy; usually only one of them is configured. {@code code} is used
 * only by research variants of balanced clustering to select a concrete strategy.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class ClusteringParameters {

    /** Number of clustering jobs per level. */
    private final int clusters_num;
    /** Number of tasks contained in each clustering job. */
    private final int clusters_size;

    /** Supported clustering methods. */
    public enum ClusteringMethod {
        HORIZONTAL,
        VERTICAL,
        NONE,
        BLOCK,
        BALANCED
    }
    /** Balanced clustering variant code. */
    private final String code;
    /** Selected clustering method. */
    private final ClusteringMethod method;

    /** @return balanced clustering variant code; usually {@code null} for other methods */
    public String getCode() {
        return code;
    }

    /** @return number of clustering jobs per level */
    public int getClustersNum() {
        return clusters_num;
    }

    /** @return number of tasks in each clustering job */
    public int getClustersSize() {
        return clusters_size;
    }

    /** @return clustering method */
    public ClusteringMethod getClusteringMethod() {
        return method;
    }

    /**
     * Create clustering parameters.
     *
     * @param cNum number of clustering jobs per level
     * @param cSize number of tasks in each clustering job
     * @param method clustering method
     * @param code balanced clustering variant code
     */
    public ClusteringParameters(int cNum, int cSize, ClusteringMethod method, String code) {
        this.clusters_num = cNum;
        this.clusters_size = cSize;
        this.method = method;
        this.code = code;
    }
}
