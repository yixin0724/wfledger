package org.workflowsim.utils;

import java.util.Calendar;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.failure.FailureGenerator;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;

public final class SimulationSession implements AutoCloseable {

    private static SimulationSession activeSession;

    private final SimulationConfig config;
    private boolean closed;
    private boolean cloudSimInitialized;

    private boolean ownsCloudSimKernel;

    private final boolean logDisabledAtOpen;

    private SimulationSession(SimulationConfig config) {
        this.config = config;
        this.logDisabledAtOpen = Log.isDisabled();
    }

    public static synchronized SimulationSession open(SimulationConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Simulation configuration cannot be null");
        }
        if (activeSession != null) {
            throw new IllegalStateException("A SimulationSession is already active in this JVM");
        }

        Parameters.reset();
        if (config.getReshiConfig() != null) {
            ReshiRegistry.configure(config.getReshiConfig());
        }
        if (config.getEbpsmConfig() != null) {
            EbpsmRegistry.configure(config.getEbpsmConfig());
        }
        if (config.getCrchConfig() != null) {
            CrchRegistry.configure(config.getCrchConfig());
        }
        Parameters.setRandomSeed(config.getRandomSeed());
        Parameters.setRuntimeScale(config.getRuntimeScale());
        Parameters.setRuntimeReferenceMips(config.getRuntimeReferenceMips());
        Parameters.setCostModel(config.getCostModel());

        Parameters.setFrequencyPolicy(config.getFrequencyPolicy());
        if (config.getRegisteredSchedulingAlgorithmProvider() != null) {

            Parameters.setRegisteredSchedulingAlgorithmProvider(config.getRegisteredSchedulingAlgorithmProvider());
        }
        if (config.getWorkflowPaths().size() == 1) {
            Parameters.init(
                    config.getVmCount(),
                    config.getWorkflowPaths().get(0),
                    null,
                    null,
                    config.getOverheadModel().createParameters(),
                    config.getClusteringParameters(),
                    config.getSchedulingAlgorithm(),
                    config.getPlanningAlgorithm(),
                    config.getReduceMethod(),
                    config.getDeadline());
        } else {
            Parameters.init(
                    config.getVmCount(),
                    config.getWorkflowPaths(),
                    null,
                    null,
                    config.getOverheadModel().createParameters(),
                    config.getClusteringParameters(),
                    config.getSchedulingAlgorithm(),
                    config.getPlanningAlgorithm(),
                    config.getReduceMethod(),
                    config.getDeadline());
        }
        Parameters.setWorkflowArrivalSeconds(config.getWorkflowArrivalSeconds());
        ReplicaCatalog.init(config.getFileSystem());
        FailureParameters.reset();
        FailureMonitor.init();
        FailureGenerator.reset();
        config.getFailureModel().applyToFailureParameters(config.getRandomSeed());
        FailureGenerator.init();

        activeSession = new SimulationSession(config);
        return activeSession;
    }

    public void initializeCloudSim(int users, Calendar calendar, boolean trace) {
        requireOpen();
        if (cloudSimInitialized) {
            throw new IllegalStateException("CloudSim is already initialized for this session");
        }
        if (users < 0) {
            throw new IllegalArgumentException("CloudSim user count cannot be negative");
        }

        ownsCloudSimKernel = true;
        try {
            CloudSim.init(users, calendar, trace, config.getCloudSimMinEventIntervalSeconds());

            if (CloudSim.getCloudInfoServiceEntityId() < 0) {
                throw new IllegalStateException("CloudSim kernel initialization failed: "
                        + "Cloud Information Service entity was not registered");
            }
            cloudSimInitialized = true;
        } catch (RuntimeException | Error failure) {
            CloudSim.reset();
            throw failure;
        }
    }

    public SimulationConfig getConfig() {
        return config;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("SimulationSession is already closed");
        }
    }

    private static void resetWorkflowGlobals() {
        try {
            FailureGenerator.reset();
        } finally {
            try {
                FailureMonitor.reset();
            } finally {
                try {
                    FailureParameters.reset();
                } finally {
                    try {
                        ReplicaCatalog.reset();
                    } finally {
                        Parameters.reset();
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (SimulationSession.class) {
            if (closed) {
                return;
            }
            try {
                if (ownsCloudSimKernel) {

                    CloudSim.reset();
                }
            } finally {
                try {
                    resetWorkflowGlobals();
                } finally {
                    try {
                        Log.setDisabled(logDisabledAtOpen);
                    } finally {
                        closed = true;
                        cloudSimInitialized = false;
                        ownsCloudSimKernel = false;
                        if (activeSession == this) {
                            activeSession = null;
                        }
                    }
                }
            }
        }
    }
}
