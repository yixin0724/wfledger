package org.workflowsim.scheduling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.utils.Parameters.FileType;

public final class CwsRankMinRoundRobinSchedulingAlgorithm extends ReadyBatchRoundRobinSchedulingAlgorithm {

    @Override
    public void run() {
        List<Cloudlet> ordered = new ArrayList<Cloudlet>();
        for (Object item : getCloudletList()) {
            ordered.add((Cloudlet) item);
        }
        Collections.sort(ordered, new Comparator<Cloudlet>() {
            @Override
            public int compare(Cloudlet first, Cloudlet second) {
                Job firstJob = asJob(first);
                Job secondJob = asJob(second);
                int byRank = Integer.compare(secondJob.getCwsRank(), firstJob.getCwsRank());
                if (byRank != 0) {
                    return byRank;
                }
                int byInput = Double.compare(realInputBytes(firstJob), realInputBytes(secondJob));
                return byInput != 0 ? byInput : Integer.compare(firstJob.getCloudletId(), secondJob.getCloudletId());
            }
        });
        setCloudletList(ordered);
        super.run();
    }

    private static Job asJob(Cloudlet cloudlet) {
        if (!(cloudlet instanceof Job)) {
            throw new IllegalStateException("CWS RankMin-RR requires WorkflowSim Jobs");
        }
        return (Job) cloudlet;
    }

    private static double realInputBytes(Job job) {
        double total = 0.0;
        for (FileItem file : job.getFileList()) {

            if (file.getType() == FileType.INPUT && file.isRealInputFile(job.getFileList())) {
                total += file.getSize();
            }
        }
        return total;
    }
}
