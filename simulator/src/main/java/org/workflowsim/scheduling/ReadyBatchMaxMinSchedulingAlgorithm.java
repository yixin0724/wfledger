package org.workflowsim.scheduling;

/**
 * Online ready-batch Max-Min (Braun et al. 2001): repeatedly assigns the Job with the largest minimum completion time.
 *
 * <p>FGCS-R6 (2026-11-25): late binding with machine ready times; see
 * {@link MachineReadyTimeSchedulingAlgorithm} for the round semantics.
 */
public class ReadyBatchMaxMinSchedulingAlgorithm extends MachineReadyTimeSchedulingAlgorithm {

    @Override
    public void run() {
        planAndBind(Selection.MAX_MIN);
    }
}
