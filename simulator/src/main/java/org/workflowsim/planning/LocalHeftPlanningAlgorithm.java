package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.workflowsim.Task;

public final class LocalHeftPlanningAlgorithm extends AbstractLocalCommPlanningAlgorithm {

    public LocalHeftPlanningAlgorithm(PlanningContext context) {
        super("LOCAL_HEFT", context);
    }

    @Override
    public void run() {
        List<Task> tasks = prepare();
        List<Task> priority = new ArrayList<Task>(tasks);
        Collections.sort(priority, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                int byRank = Double.compare(upwardRankOf(second), upwardRankOf(first));
                return byRank != 0 ? byRank : Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        double stageInFinish = stageInFinishTime();
        for (Task task : priority) {
            allocate(task, null, stageInFinish);
        }
    }
}
