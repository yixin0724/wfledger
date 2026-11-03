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
package org.workflowsim.examples.failure.clustering;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.examples.ExampleCliSupport;
import org.workflowsim.failure.FailureGenerator;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.PeriodicalDistributionGenerator;
import org.workflowsim.utils.PeriodicalSignal;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Historical dynamic reclustering teaching example that uses a periodic failure signal.
 *
 * <p>The class combines a periodic signal, a failure distribution, and the reclustering path into
 * one legacy executable scenario. All parameters are abstract scenario assumptions; it is not a
 * standard research experiment entry point, and it must not be used to infer the periodic failure
 * behavior of a real platform.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Dec 31, 2013
 */
public class FaultTolerantClusteringExample7 extends FaultTolerantClusteringExample1 {

    private static final String EXAMPLE_ID = "fault-clustering-periodic";
    /**
     * Compatibility fallback scale after the periodic cache is exhausted, together with the prior
     * baseline of the legacy estimator; it does not represent a real failure rate.
     */
    private static final double DEFAULT_FAILURE_INTERVAL_SCALE = 100000.0;
    /** High-failure phase scale at a short interval under the fixed teaching seed. */
    private static final double DEFAULT_HIGH_FAILURE_INTERVAL_SCALE = 30000.0;
    /** Low-failure phase scale at a long interval under the fixed teaching seed. */
    private static final double DEFAULT_LOW_FAILURE_INTERVAL_SCALE = 180000.0;

    /**
     * Run the fault-tolerant scenario with a periodic two-level failure interval.
     *
     * <p>Supports {@code -c} (SR/DR/NOOP/DC/VR), {@code -d}, {@code -b},
     * {@code -q}/{@code -s} (queue Gamma parameters), {@code -w} (queue prior strength),
     * {@code -p}/{@code -t} (legacy failure estimator parameters), {@code -e} (period),
     * {@code -n} (high-failure phase portion), {@code -h}/{@code -l} (Weibull interval scales of
     * the high/low failure phase), and {@code -r} (root seed). The high-failure phase scale must be
     * smaller than the low-failure phase scale. The actual sampling of periodic arrivals is
     * controlled by {@code -h}/{@code -l}; {@code -p}/{@code -t} are retained as legacy estimator
     * priors and as compatibility fallback parameters after the cache is exhausted.</p>
     *
     * @param args legacy argument sequence of single-character options and values
     */
    public static void main(String[] args) {
        try {
            Map<String, String> options = ExampleCliSupport.parseValueOptions(
                    args, "-c", "-d", "-b", "-w", "-q", "-s", "-p", "-t", "-e", "-n", "-h", "-l", "-r");
            long rootSeed = ExampleCliSupport.longValue(
                    "-r",
                    ExampleCliSupport.optionOrDefault(
                            options, "-r", Long.toString(ExampleCliSupport.LEGACY_DEMO_SEED)));
            ExampleCliSupport.begin(EXAMPLE_ID, rootSeed);

            int vmNum = 20;
            String daxPath = ExampleCliSupport.requireDax(
                    ExampleCliSupport.optionOrDefault(options, "-d", ExampleCliSupport.DEFAULT_DAX_PATH));
            String clustering = ExampleCliSupport.allowedValue(
                    "-c", ExampleCliSupport.optionOrDefault(options, "-c", "DR"), "SR", "DR", "NOOP", "DC", "VR");
            double runtimeScaleTenths =
                    ExampleCliSupport.positiveFinite("-b", ExampleCliSupport.optionOrDefault(options, "-b", "10"));
            double queuePriorWeight =
                    ExampleCliSupport.positiveFinite("-w", ExampleCliSupport.optionOrDefault(options, "-w", "3"));
            double queueScale =
                    ExampleCliSupport.positiveFinite("-q", ExampleCliSupport.optionOrDefault(options, "-q", "50"));
            double queueShape =
                    ExampleCliSupport.positiveFinite("-s", ExampleCliSupport.optionOrDefault(options, "-s", "3"));
            double failureScale = ExampleCliSupport.positiveFinite(
                    "-p",
                    ExampleCliSupport.optionOrDefault(options, "-p", Double.toString(DEFAULT_FAILURE_INTERVAL_SCALE)));
            double failurePriorWeight =
                    ExampleCliSupport.positiveFinite("-t", ExampleCliSupport.optionOrDefault(options, "-t", "30"));
            double period =
                    ExampleCliSupport.positiveFinite("-e", ExampleCliSupport.optionOrDefault(options, "-e", "40000"));
            double highFailurePortion =
                    ExampleCliSupport.openUnitInterval("-n", ExampleCliSupport.optionOrDefault(options, "-n", "0.1"));
            double highFailureIntervalScale = ExampleCliSupport.positiveFinite(
                    "-h",
                    ExampleCliSupport.optionOrDefault(
                            options, "-h", Double.toString(DEFAULT_HIGH_FAILURE_INTERVAL_SCALE)));
            double lowFailureIntervalScale = ExampleCliSupport.positiveFinite(
                    "-l",
                    ExampleCliSupport.optionOrDefault(
                            options, "-l", Double.toString(DEFAULT_LOW_FAILURE_INTERVAL_SCALE)));
            if (highFailureIntervalScale >= lowFailureIntervalScale) {
                throw new IllegalArgumentException(
                        "-h must be smaller than -l so the high-failure phase " + "has a shorter interval scale");
            }
            double queuePriorScale = ExampleCliSupport.positiveFiniteProduct("-w * -q", queuePriorWeight, queueScale);
            double failurePriorScale =
                    ExampleCliSupport.positiveFiniteProduct("-t * -p", failurePriorWeight, failureScale);

            ClusteringParameters.ClusteringMethod clusteringMethod = "VR".equals(clustering)
                    ? ClusteringParameters.ClusteringMethod.VERTICAL
                    : ClusteringParameters.ClusteringMethod.HORIZONTAL;
            FailureParameters.FTCluteringAlgorithm strategy = strategyFor(clustering);
            int maxLevel = 11;
            // To keep the semantics clear, upper is the long-interval low-failure phase and
            // lower is the short-interval high-failure phase.
            PeriodicalSignal signal = new PeriodicalSignal(
                    period, lowFailureIntervalScale, highFailureIntervalScale, highFailurePortion, false);

            DistributionGenerator[][] failureGenerators = new DistributionGenerator[vmNum][maxLevel];
            // Historical E7 uses one shared global failure clock; it is not a VM-depth independent
            // failure model.
            DistributionGenerator failureGenerator = new PeriodicalDistributionGenerator(
                    DistributionGenerator.DistributionFamily.WEIBULL,
                    failureScale,
                    0.78,
                    failurePriorWeight,
                    failurePriorScale,
                    0.78,
                    signal,
                    "example.fault-clustering-periodic.legacy-shared-clock");
            for (int level = 0; level < maxLevel; level++) {
                for (int vmId = 0; vmId < vmNum; vmId++) {
                    failureGenerators[vmId][level] = failureGenerator;
                }
            }

            Map<Integer, DistributionGenerator> queueDelay = new HashMap<Integer, DistributionGenerator>();
            DistributionGenerator queueGenerator = new DistributionGenerator(
                    DistributionGenerator.DistributionFamily.GAMMA,
                    queueScale,
                    queueShape,
                    queuePriorWeight,
                    queuePriorScale,
                    queueShape,
                    "example.fault-clustering-periodic.queue");
            for (int level = 0; level < maxLevel; level++) {
                queueDelay.put(level, queueGenerator);
            }

            OverheadParameters overhead = new OverheadParameters(
                    0,
                    new HashMap<Integer, DistributionGenerator>(),
                    queueDelay,
                    new HashMap<Integer, DistributionGenerator>(),
                    new HashMap<Integer, DistributionGenerator>(),
                    0);
            ClusteringParameters clusteringParameters = new ClusteringParameters(vmNum, 0, clusteringMethod, null);

            FailureParameters.init(
                    strategy,
                    FailureParameters.FTCMonitor.MONITOR_VM_JOB,
                    FailureParameters.FTCFailure.FAILURE_VM_JOB,
                    failureGenerators);
            Parameters.init(
                    vmNum,
                    daxPath,
                    null,
                    null,
                    overhead,
                    clusteringParameters,
                    Parameters.SchedulingAlgorithm.MINMIN,
                    Parameters.PlanningAlgorithm.INVALID,
                    null,
                    0);
            Parameters.setRuntimeScale(runtimeScaleTenths / 10.0);
            ReplicaCatalog.init(ReplicaCatalog.FileSystem.SHARED);
            FailureMonitor.init();
            FailureGenerator.init();

            CloudSim.init(1, Calendar.getInstance(), false);
            WorkflowDatacenter datacenter = createDatacenter("Datacenter_0");
            WorkflowPlanner planner = new WorkflowPlanner("planner_0", 1);
            WorkflowEngine engine = planner.getWorkflowEngine();
            List<CondorVM> virtualMachines = createVM(engine.getSchedulerId(0), Parameters.getVmNum());
            engine.submitVmList(virtualMachines, 0);
            engine.bindSchedulerDatacenter(datacenter.getId(), 0);

            CloudSim.startSimulation();
            List<Job> jobs = engine.getJobsReceivedList();
            CloudSim.stopSimulation();
            printJobList(jobs);
            ExampleCliSupport.reportCompletion(jobs);
        } catch (Exception exception) {
            throw ExampleCliSupport.failure(EXAMPLE_ID, exception);
        } finally {
            ExampleCliSupport.end();
        }
    }

    private static FailureParameters.FTCluteringAlgorithm strategyFor(String clustering) {
        if ("SR".equals(clustering)) {
            return FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_SR;
        }
        if ("DR".equals(clustering)) {
            return FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_DR;
        }
        if ("NOOP".equals(clustering)) {
            return FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP;
        }
        if ("DC".equals(clustering)) {
            return FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_DC;
        }
        if ("VR".equals(clustering)) {
            return FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_VERTICAL;
        }
        throw new IllegalArgumentException("Unsupported fault-tolerant clustering strategy: " + clustering);
    }
}
