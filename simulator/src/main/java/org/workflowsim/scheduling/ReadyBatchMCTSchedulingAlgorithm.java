package org.workflowsim.scheduling;

/** Online ready-batch MCT (Maheswaran et al. */
public class ReadyBatchMCTSchedulingAlgorithm extends MachineReadyTimeSchedulingAlgorithm {

    @Override
    public void run() {
        planAndBind(Selection.QUEUE_ORDER);
    }
}
