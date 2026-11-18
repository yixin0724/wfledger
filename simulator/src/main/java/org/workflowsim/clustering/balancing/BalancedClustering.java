/**
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim.clustering.balancing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.Task;
import org.workflowsim.clustering.BasicClustering;
import org.workflowsim.clustering.TaskSet;
import org.workflowsim.clustering.balancing.methods.ChildAwareHorizontalClustering;
import org.workflowsim.clustering.balancing.methods.HorizontalDistanceBalancing;
import org.workflowsim.clustering.balancing.methods.HorizontalImpactBalancing;
import org.workflowsim.clustering.balancing.methods.HorizontalRandomClustering;
import org.workflowsim.clustering.balancing.methods.HorizontalRuntimeBalancing;
import org.workflowsim.clustering.balancing.methods.VerticalBalancing;
import org.workflowsim.clustering.balancing.metrics.DistanceVariance;
import org.workflowsim.clustering.balancing.metrics.HorizontalRuntimeVariance;
import org.workflowsim.clustering.balancing.metrics.ImpactFactorVariance;
import org.workflowsim.clustering.balancing.metrics.PipelineRuntimeVariance;
import org.workflowsim.utils.Parameters;

public class BalancedClustering extends BasicClustering {

    private final int clusterNum;

    private final Map<Task, TaskSet> mTask2TaskSet;

    private final Map<TaskSet, Integer> mTaskSet2Depth;

    public BalancedClustering(int clusterNum) {
        super();
        this.clusterNum = clusterNum;
        this.mTask2TaskSet = new HashMap<>();
        mTaskSet2Depth = new HashMap<>();
    }

    public void cleanTaskSetChecked() {
        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            set.hasChecked = false;
        }
    }

    private void addImpact(TaskSet set, double impact) {

        set.setImpactFafctor(set.getImpactFactor() + impact);
        int size = set.getParentList().size();
        if (size > 0) {
            double avg = impact / size;
            for (TaskSet parent : set.getParentList()) {
                addImpact(parent, avg);
            }
        }
    }

    public void printMetrics() {
        Map<Integer, List<TaskSet>> map = getCurrentTaskSetAtLevels();
        for (TaskSet set : mTask2TaskSet.values()) {
            set.setImpactFafctor(0.0);
        }

        int maxDepth = 0;
        for (Entry<Integer, List<TaskSet>> entry : map.entrySet()) {
            int depth = entry.getKey();
            if (depth > maxDepth) {
                maxDepth = depth;
            }
        }
        List<TaskSet> exits = map.get(maxDepth);
        double avg = 1.0 / exits.size();
        for (TaskSet set : exits) {

            addImpact(set, avg);
        }

        for (Entry<Integer, List<TaskSet>> entry : map.entrySet()) {
            int depth = entry.getKey();
            List<TaskSet> list = entry.getValue();

            double hrv = new HorizontalRuntimeVariance().getMetric(list);

            double ifv = new ImpactFactorVariance().getMetric(list);

            double prv = new PipelineRuntimeVariance().getMetric(list);

            double dv = new DistanceVariance().getMetric(list);
            Log.printLine("HRV " + depth + " " + list.size()
                    + " " + hrv + "\nIFV " + depth + " "
                    + list.size() + " " + ifv + "\nPRV " + depth
                    + " " + list.size() + " " + prv + "\nDV " + depth + " " + list.size() + " " + dv);
        }
    }

    public Map<Integer, List<TaskSet>> getCurrentTaskSetAtLevels() {

        Map<Integer, List<TaskSet>> map = new HashMap<>();
        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;
                int depth = getDepth(set);
                if (!map.containsKey(depth)) {
                    map.put(depth, new ArrayList<>());
                }
                List list = map.get(depth);
                list.add(set);
            }
        }
        mTaskSet2Depth.clear();

        cleanTaskSetChecked();
        return map;
    }

    private int getDepth(TaskSet set) {
        if (mTaskSet2Depth.containsKey(set)) {
            return mTaskSet2Depth.get(set);
        } else {
            int depth = 0;
            for (TaskSet parent : set.getParentList()) {
                int curDepth = getDepth(parent);
                if (curDepth > depth) {
                    depth = curDepth;
                }
            }
            depth++;
            mTaskSet2Depth.put(set, depth);
            return depth;
        }
    }

    private boolean check(Task ancestor, Task set) {
        if (ancestor == null || set == null) {
            return false;
        }
        if (ancestor == set) {
            return true;
        }
        for (Task parent : set.getParentList()) {
            if (check(ancestor, parent)) {
                return true;
            } else {

            }
        }
        return false;
    }

    private final Map<Task, Task> mRecover = new HashMap<>();

    private void remove() {

        for (Task set : this.getTaskList()) {
            if (set.getChildList().size() >= 2) {
                for (int i = 0; i < set.getChildList().size(); i++) {
                    Task children = (Task) set.getChildList().get(i);
                    for (int j = i + 1; j < set.getChildList().size(); j++) {
                        Task another = (Task) set.getChildList().get(j);

                        if (children.getDepth() > another.getDepth()) {
                            if (check(another, children)) {

                                set.getChildList().remove(children);
                                children.getParentList().remove(set);
                                i--;
                                mRecover.put(set, children);

                                break;
                            } else {

                            }
                        }
                        if (another.getDepth() > children.getDepth()) {
                            if (check(children, another)) {
                                set.getChildList().remove(another);
                                another.getParentList().remove(set);
                                i--;
                                mRecover.put(set, another);

                                break;
                            } else {

                            }
                        }
                    }
                }
            }
        }
    }

    private void recover() {
        for (Entry<Task, Task> entry : mRecover.entrySet()) {
            Task set = entry.getKey();
            Task children = entry.getValue();
            set.getChildList().add(children);
            children.getParentList().add(set);
        }
    }

    @Override
    public void run() {

        if (clusterNum > 0) {
            for (Task task : getTaskList()) {
                TaskSet set = new TaskSet();
                set.addTask(task);
                mTask2TaskSet.put(task, set);
            }
        }

        remove();
        updateTaskSetDependencies();

        printMetrics();
        String code = Parameters.getClusteringParameters().getCode();
        Map<Integer, List<TaskSet>> map = getCurrentTaskSetAtLevels();
        if (code != null) {
            for (char c : code.toCharArray()) {

                switch (c) {
                    case 'v':
                        VerticalBalancing v = new VerticalBalancing(map, this.mTask2TaskSet, this.clusterNum);
                        v.run();
                        break;
                    case 'c':
                        ChildAwareHorizontalClustering ch =
                                new ChildAwareHorizontalClustering(map, this.mTask2TaskSet, this.clusterNum);
                        ch.run();
                        updateTaskSetDependencies();
                        break;
                    case 'r':
                        HorizontalRuntimeBalancing r =
                                new HorizontalRuntimeBalancing(map, this.mTask2TaskSet, this.clusterNum);
                        r.run();
                        updateTaskSetDependencies();
                        break;
                    case 'i':
                        HorizontalImpactBalancing i =
                                new HorizontalImpactBalancing(map, this.mTask2TaskSet, this.clusterNum);
                        i.run();
                        break;
                    case 'd':
                        HorizontalDistanceBalancing d =
                                new HorizontalDistanceBalancing(map, this.mTask2TaskSet, this.clusterNum);
                        d.run();
                        break;
                    case 'h':
                        HorizontalRandomClustering h =
                                new HorizontalRandomClustering(map, this.mTask2TaskSet, this.clusterNum);
                        h.run();
                        break;
                    default:
                        break;
                }
            }
            printMetrics();
        }

        printOut();

        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;
                addTasks2Job(set.getTaskList());
            }
        }

        cleanTaskSetChecked();

        updateDependencies();
        addClustDelay();

        recover();
    }

    private void printOut() {
        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;

                Log.printLine("Job");
                for (Task task : set.getTaskList()) {
                    Log.printLine(
                            "Task " + task.getCloudletId() + " " + task.getImpact() + " " + task.getCloudletLength());
                }
            }
        }

        cleanTaskSetChecked();
    }

    private void updateTaskSetDependencies() {

        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;
                set.getChildList().clear();
                set.getParentList().clear();
                for (Task task : set.getTaskList()) {
                    for (Task parent : task.getParentList()) {
                        TaskSet parentSet = mTask2TaskSet.get(parent);
                        if (!set.getParentList().contains(parentSet) && set != parentSet) {
                            set.getParentList().add(parentSet);
                        }
                    }
                    for (Task child : task.getChildList()) {
                        TaskSet childSet = mTask2TaskSet.get(child);
                        if (!set.getChildList().contains(childSet) && set != childSet) {
                            set.getChildList().add(childSet);
                        }
                    }
                }
            }
        }

        cleanTaskSetChecked();
    }
}
