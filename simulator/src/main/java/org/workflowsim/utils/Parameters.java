/**
 * Copyright 2012-2013 University Of Southern California
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.platform.PlatformProfile;

public class Parameters {

    public enum SchedulingAlgorithm {
        READY_BATCH_MINMIN,
        READY_BATCH_MAXMIN,
        READY_BATCH_MCT,
        READY_BATCH_ROUNDROBIN,

        DATA,
        STATIC,
        FCFS,

        CARBON_WINDOW_DEFER,

        CWS_RANKMIN_RR,

        RESHI_C,

        RESHI_M,

        EBPSM,

        DVFS_SLACK,

        CRCH,
        INVALID
    }

    public enum PlanningAlgorithm {
        INVALID,
        RANDOM,

        STATIC_OLB,
        STATIC_MET,
        STATIC_MCT,
        STATIC_MINMIN,
        STATIC_MAXMIN,
        STATIC_SUFFERAGE,
        STATIC_ROUND_ROBIN,

        SHARED_STORAGE_HEFT,
        SHARED_STORAGE_CPOP,
        SHARED_STORAGE_DLS,
        SHARED_STORAGE_ETF,
        SHARED_STORAGE_PEFT,

        CAWOSCHED_SLACK,

        CAWOSCHED_PRESSURE,

        NACO_GA_HEFT,

        GREEN_HEFT,

        MO_HEFT,

        IC_PCP,

        NETWORK_HEFT,

        DVFS_SLACK,

        DHEFT,

        PSO,

        LOCAL_HEFT,

        LOCAL_CPOP,

        LOCAL_PEFT
    }

    public enum FileType {
        NONE(0),
        INPUT(1),
        OUTPUT(2);
        public final int value;

        private FileType(int fType) {
            this.value = fType;
        }
    }

    public enum ClassType {
        STAGE_IN(1),
        COMPUTE(2),
        STAGE_OUT(3),
        CLEAN_UP(4);
        public final int value;

        private ClassType(int cType) {
            this.value = cType;
        }
    }

    public enum CostModel {
        DATACENTER(1),
        VM(2);
        public final int value;

        private CostModel(int model) {
            this.value = model;
        }
    }

    public static String SOURCE = "source";

    public static final int BASE = 0;

    private static SchedulingAlgorithm schedulingAlgorithm;

    private static org.workflowsim.experiment.registry.SchedulingAlgorithmProvider
            registeredSchedulingAlgorithmProvider;

    private static PlanningAlgorithm planningAlgorithm;

    private static String reduceMethod;

    private static int vmNum;

    private static String daxPath;

    private static List<String> daxPaths;

    private static String runtimePath;

    private static String datasizePath;

    private static final String VERSION = "1.1.0";

    private static final String NOTE = " supports planning algorithm at Nov 9, 2013";

    private static OverheadParameters oParams;

    private static ClusteringParameters cParams;

    private static long deadline;

    private static double[][] bandwidths;

    private static int maxDepth;

    private static final String INVALID = "Invalid";

    private static double runtime_scale = 1.0;

    private static double runtimeReferenceMips = 1000.0;

    private static long randomSeed = 0L;

    private static CostModel costModel = CostModel.DATACENTER;

    private static SimulationConfig.FrequencyPolicy frequencyPolicy = SimulationConfig.FrequencyPolicy.DEFAULT;

    private static List<Double> workflowArrivalSeconds;

    private static List<PlatformProfile.CarbonWindow> carbonWindows = Collections.emptyList();

    public static void init(
            int vm,
            String dax,
            String runtime,
            String datasize,
            OverheadParameters op,
            ClusteringParameters cp,
            SchedulingAlgorithm scheduler,
            PlanningAlgorithm planner,
            String rMethod,
            long dl) {

        cParams = cp;
        vmNum = vm;
        daxPath = dax;
        daxPaths = null;
        runtimePath = runtime;
        datasizePath = datasize;

        oParams = op;
        schedulingAlgorithm = scheduler;
        planningAlgorithm = planner;
        reduceMethod = rMethod;
        deadline = dl;
        maxDepth = 0;
        SimulationRandom.reset(randomSeed);
    }

    public static void init(
            int vm,
            List<String> dax,
            String runtime,
            String datasize,
            OverheadParameters op,
            ClusteringParameters cp,
            SchedulingAlgorithm scheduler,
            PlanningAlgorithm planner,
            String rMethod,
            long dl) {

        cParams = cp;
        vmNum = vm;
        daxPath = null;
        daxPaths = dax == null ? null : new ArrayList<>(dax);
        runtimePath = runtime;
        datasizePath = datasize;

        oParams = op;
        schedulingAlgorithm = scheduler;
        planningAlgorithm = planner;
        reduceMethod = rMethod;
        deadline = dl;
        maxDepth = 0;
        SimulationRandom.reset(randomSeed);
    }

    public static OverheadParameters getOverheadParams() {
        return oParams;
    }

    public static String getReduceMethod() {
        if (reduceMethod != null) {
            return reduceMethod;
        } else {
            return INVALID;
        }
    }

    public static String getDaxPath() {
        return daxPath;
    }

    public static String getRuntimePath() {
        return runtimePath;
    }

    public static String getDatasizePath() {
        return datasizePath;
    }

    public static int getVmNum() {
        return vmNum;
    }

    public static CostModel getCostModel() {
        return costModel;
    }

    public static void setVmNum(int num) {
        vmNum = num;
    }

    public static ClusteringParameters getClusteringParameters() {
        return cParams;
    }

    public static SchedulingAlgorithm getSchedulingAlgorithm() {
        return schedulingAlgorithm;
    }

    public static void setRegisteredSchedulingAlgorithmProvider(
            org.workflowsim.experiment.registry.SchedulingAlgorithmProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("Registered scheduling provider cannot be null");
        }
        registeredSchedulingAlgorithmProvider = provider;
    }

    public static org.workflowsim.experiment.registry.SchedulingAlgorithmProvider
            getRegisteredSchedulingAlgorithmProvider() {
        return registeredSchedulingAlgorithmProvider;
    }

    public static PlanningAlgorithm getPlanningAlgorithm() {
        return planningAlgorithm;
    }

    public static String getVersion() {
        return VERSION;
    }

    public static void printVersion() {
        Log.printLine("WorkflowSim Version: " + VERSION);
        Log.printLine("Change Note: " + NOTE);
    }

    public static long getDeadline() {
        return deadline;
    }

    public static int getMaxDepth() {
        return maxDepth;
    }

    public static void setMaxDepth(int depth) {
        maxDepth = depth;
    }

    public static void setRuntimeScale(double scale) {
        if (scale <= 0.0 || Double.isInfinite(scale) || Double.isNaN(scale)) {
            throw new IllegalArgumentException("Runtime scale must be finite and positive");
        }
        runtime_scale = scale;
    }

    public static void setCostModel(CostModel model) {
        if (model == null) {
            throw new IllegalArgumentException("Cost model cannot be null");
        }
        costModel = model;
    }

    public static void setFrequencyPolicy(SimulationConfig.FrequencyPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Frequency policy cannot be null");
        }
        frequencyPolicy = policy;
    }

    public static SimulationConfig.FrequencyPolicy getFrequencyPolicy() {
        return frequencyPolicy;
    }

    public static double getRuntimeScale() {
        return runtime_scale;
    }

    public static void setRuntimeReferenceMips(double mips) {
        if (mips <= 0.0 || Double.isInfinite(mips) || Double.isNaN(mips)) {
            throw new IllegalArgumentException("Runtime reference MIPS must be finite and positive");
        }
        runtimeReferenceMips = mips;
    }

    public static double getRuntimeReferenceMips() {
        return runtimeReferenceMips;
    }

    public static void setRandomSeed(long seed) {
        randomSeed = seed;
        SimulationRandom.reset(seed);
    }

    public static long getRandomSeed() {
        return randomSeed;
    }

    public static void reset() {
        SOURCE = "source";
        schedulingAlgorithm = SchedulingAlgorithm.INVALID;
        registeredSchedulingAlgorithmProvider = null;
        planningAlgorithm = PlanningAlgorithm.INVALID;
        reduceMethod = null;
        vmNum = 0;
        daxPath = null;
        daxPaths = null;
        runtimePath = null;
        datasizePath = null;
        oParams = null;
        cParams = null;
        deadline = 0L;
        bandwidths = null;
        maxDepth = 0;
        runtime_scale = 1.0;
        runtimeReferenceMips = 1000.0;
        randomSeed = 0L;
        costModel = CostModel.DATACENTER;
        frequencyPolicy = SimulationConfig.FrequencyPolicy.DEFAULT;
        workflowArrivalSeconds = null;
        carbonWindows = Collections.emptyList();
        ReshiRegistry.clear();
        EbpsmRegistry.clear();
        DvfsSlackRegistry.clear();
        CrchRegistry.clear();
        SimulationRandom.reset(randomSeed);
    }

    public static void setCarbonWindows(List<PlatformProfile.CarbonWindow> windows) {
        carbonWindows = windows == null
                ? Collections.<PlatformProfile.CarbonWindow>emptyList()
                : Collections.unmodifiableList(new ArrayList<PlatformProfile.CarbonWindow>(windows));
    }

    public static List<PlatformProfile.CarbonWindow> getCarbonWindows() {
        return carbonWindows;
    }

    public static List<String> getDAXPaths() {
        return daxPaths == null ? null : Collections.unmodifiableList(daxPaths);
    }

    public static void setWorkflowArrivalSeconds(List<Double> arrivalSeconds) {
        workflowArrivalSeconds = arrivalSeconds == null ? null : new ArrayList<>(arrivalSeconds);
    }

    public static List<Double> getWorkflowArrivalSeconds() {
        return workflowArrivalSeconds == null ? null : Collections.unmodifiableList(workflowArrivalSeconds);
    }
}
