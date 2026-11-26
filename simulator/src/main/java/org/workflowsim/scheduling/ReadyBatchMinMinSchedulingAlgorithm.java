package org.workflowsim.scheduling;

/** Online ready-batch Min-Min (Braun et al. */
public class ReadyBatchMinMinSchedulingAlgorithm extends MachineReadyTimeSchedulingAlgorithm {

    @Override
    public void run() {
        planAndBind(Selection.MIN_MIN);
    }
}
