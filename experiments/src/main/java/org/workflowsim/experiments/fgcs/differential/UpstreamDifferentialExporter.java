package org.workflowsim.experiments.fgcs.differential;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.experiments.fgcs.FgcDatasets;
import org.workflowsim.experiments.fgcs.FgcPlatforms;
import org.workflowsim.experiments.fgcs.FgcStudySupport;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class UpstreamDifferentialExporter {

    private UpstreamDifferentialExporter() {}

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0
                ? Paths.get("paper/tmp/experiments/differential/current-observations.json")
                : Paths.get(args[0]);
        JsonArray results = new JsonArray();
        for (String shape : new String[] {"chain", "fan", "independent"}) {
            String workflow = FgcDatasets.smallDag(output.getParent().resolve("inputs"), shape)
                    .toString();
            for (Parameters.SchedulingAlgorithm scheduler : new Parameters.SchedulingAlgorithm[] {
                Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN, Parameters.SchedulingAlgorithm.STATIC
            }) {
                String label = shape + "-" + scheduler + "-s7";
                results.add(run(workflow, label, scheduler));
            }
        }
        for (String workflow : new String[] {FgcDatasets.MONTAGE_50, FgcDatasets.CYBERSHAKE_50, FgcDatasets.SIPHT_60}) {
            for (Parameters.SchedulingAlgorithm scheduler : new Parameters.SchedulingAlgorithm[] {
                Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN, Parameters.SchedulingAlgorithm.STATIC
            }) {
                String label = workflow.substring(workflow.lastIndexOf('/') + 1) + "-" + scheduler + "-s7";
                results.add(run(FgcDatasets.real(workflow), label, scheduler));
            }
        }
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.write(
                output,
                new com.google.gson.GsonBuilder()
                        .setPrettyPrinting()
                        .create()
                        .toJson(results)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("EXPORTED_CURRENT_OBSERVATIONS=" + results.size());
    }

    private static JsonObject run(String workflow, String label, Parameters.SchedulingAlgorithm scheduler)
            throws org.workflowsim.exception.SimulationExecutionException {
        SimulationConfig config = FgcStudySupport.base(workflow, 4)
                .planningAlgorithm(
                        scheduler == Parameters.SchedulingAlgorithm.STATIC
                                ? Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT
                                : Parameters.PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(scheduler)
                .build();
        PlatformProfile platform = FgcPlatforms.flat(4, 2);
        SimulationReport report = new SimulationRunner().run(config, platform);
        JsonObject result = new JsonObject();
        result.addProperty("label", label);
        result.addProperty("workflow", workflow);
        result.addProperty("scheduler", scheduler.name());
        result.addProperty("vmCount", 4);
        result.addProperty("speedRatio", 2);
        result.addProperty("logicalTasks", report.getMetrics().getLogicalTaskCount());
        result.addProperty("completed", report.isWorkflowCompletedSuccessfully());
        JsonArray tasks = new JsonArray();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != Parameters.ClassType.COMPUTE.value) {
                continue;
            }
            for (Integer taskId : job.getTaskIds()) {
                JsonObject task = new JsonObject();
                task.addProperty("taskId", taskId);
                task.addProperty("vmId", job.getVmId());
                task.addProperty("start", job.getStartTime());
                task.addProperty("jobFinish", job.getFinishTime());
                task.addProperty("status", job.getStatus());
                tasks.add(task);
            }
        }
        result.add("tasks", tasks);
        return result;
    }
}
