package org.workflowsim.planning.greenheft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Immutable GreenHEFT task with compute, energy, and dependency inputs. */
public final class GreenHeftTask {
    private final int taskId;
    private final Map<Integer, Double> computeSecondsByVm;
    private final Map<Integer, Double> energyJoulesByVm;
    private final List<Integer> parentTaskIds;
    private final Map<Integer, Double> communicationSecondsByChild;

    public GreenHeftTask(
            int taskId,
            Map<Integer, Double> computeSecondsByVm,
            Map<Integer, Double> energyJoulesByVm,
            List<Integer> parentTaskIds,
            Map<Integer, Double> communicationSecondsByChild) {
        if (taskId < 0
                || computeSecondsByVm == null
                || computeSecondsByVm.isEmpty()
                || energyJoulesByVm == null
                || parentTaskIds == null
                || communicationSecondsByChild == null) {
            throw new IllegalArgumentException("Invalid GreenHEFT task declarations");
        }
        for (Map.Entry<Integer, Double> entry : computeSecondsByVm.entrySet()) {
            Double energy = energyJoulesByVm.get(entry.getKey());
            if (!(entry.getValue() > 0.0)
                    || !Double.isFinite(entry.getValue())
                    || energy == null
                    || energy < 0.0
                    || !Double.isFinite(energy)) {
                throw new IllegalArgumentException(
                        "GreenHEFT requires positive compute and non-negative energy per VM");
            }
        }
        this.taskId = taskId;
        this.computeSecondsByVm = Collections.unmodifiableMap(computeSecondsByVm);
        this.energyJoulesByVm = Collections.unmodifiableMap(energyJoulesByVm);
        this.parentTaskIds = Collections.unmodifiableList(new ArrayList<Integer>(parentTaskIds));
        this.communicationSecondsByChild = Collections.unmodifiableMap(communicationSecondsByChild);
    }

    public int getTaskId() {
        return taskId;
    }

    public Map<Integer, Double> getComputeSecondsByVm() {
        return computeSecondsByVm;
    }

    public Map<Integer, Double> getEnergyJoulesByVm() {
        return energyJoulesByVm;
    }

    public List<Integer> getParentTaskIds() {
        return parentTaskIds;
    }

    public Map<Integer, Double> getCommunicationSecondsByChild() {
        return communicationSecondsByChild;
    }
}
