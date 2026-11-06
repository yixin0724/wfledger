package org.workflowsim.planning.moheft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** MOHEFT Pareto front plus one deterministic balanced schedule for execution. */
public final class MoHeftResult {
    private final List<MoHeftSchedule> paretoFront;
    private final MoHeftSchedule balancedSchedule;

    MoHeftResult(List<MoHeftSchedule> paretoFront, MoHeftSchedule balancedSchedule) {
        this.paretoFront = Collections.unmodifiableList(new ArrayList<MoHeftSchedule>(paretoFront));
        this.balancedSchedule = balancedSchedule;
    }

    public List<MoHeftSchedule> getParetoFront() {
        return paretoFront;
    }

    public MoHeftSchedule getBalancedSchedule() {
        return balancedSchedule;
    }
}
