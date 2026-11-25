package org.workflowsim.scheduling;

/**
 * Online ready-batch MCT (Maheswaran et al. 1999 immediate mode): assigns Jobs in queue order to
 * their minimum completion time.
 *
 * <p>FGCS-R6 (2026-11-25): late binding with machine ready times; see
 * {@link MachineReadyTimeSchedulingAlgorithm} for the round semantics.
 */
public class ReadyBatchMCTSchedulingAlgorithm extends MachineReadyTimeSchedulingAlgorithm {

    @Override
    public void run() {
        planAndBind(Selection.QUEUE_ORDER);
    }
}
