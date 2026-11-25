package org.workflowsim.scheduling;

/**
 * Online ready-batch Min-Min (Braun et al. 2001): repeatedly assigns the Job with the smallest minimum completion time.
 *
 * <p>FGCS-R6 (2026-11-25): late binding with machine ready times; see
 * {@link MachineReadyTimeSchedulingAlgorithm} for the round semantics.
 */
public class ReadyBatchMinMinSchedulingAlgorithm extends MachineReadyTimeSchedulingAlgorithm {

    @Override
    public void run() {
        planAndBind(Selection.MIN_MIN);
    }
}
