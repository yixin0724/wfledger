package org.workflowsim.scheduling;

/** Online ready-batch Max-Min (Braun et al. */
public class ReadyBatchMaxMinSchedulingAlgorithm extends MachineReadyTimeSchedulingAlgorithm {

    @Override
    public void run() {
        planAndBind(Selection.MAX_MIN);
    }
}
