package org.workflowsim.experiments.workbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.ExperimentEvidenceContext;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class Workbench {
    private Workbench() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException(
                    "Usage: Workbench validate <config> | run <config> <output-root> | history <output-root> | "
                            + "report <manifest> <new-report.html>");
        }
        switch (args[0]) {
            case "validate":
                requireLength(args, 2);
                WorkbenchConfig config = WorkbenchConfig.read(Paths.get(args[1]));
                config.validateInputs();
                System.out.println("WORKBENCH_VALIDATED runs=" + config.getRunCount());
                return;
            case "run":
                requireLength(args, 3);
                Path result = run(Paths.get(args[1]), Paths.get(args[2]));
                System.out.println("WORKBENCH_REPORT " + result.resolve("report.html"));
                if (!"COMPLETED_SUCCESSFULLY"
                        .equals(read(result.resolve("experiment.json"))
                                .get("status")
                                .getAsString())) {
                    throw new IllegalStateException("Experiment contains failed runs; see its report and history");
                }
                return;
            case "history":
                requireLength(args, 2);
                System.out.println("WORKBENCH_HISTORY " + history(Paths.get(args[1])));
                return;
            case "report":
                requireLength(args, 3);
                Path target = Paths.get(args[2]);
                if (Files.exists(target)) {
                    throw new IOException("Report output already exists: " + target);
                }
                HtmlReports.ValidatedReport evidence = HtmlReports.validatedReport(Paths.get(args[1]));
                JsonObject manifest = evidence.getManifest();
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                JsonObject configuration = manifest.getAsJsonObject("configuration");
                String planner = configuration.get("planningAlgorithm").getAsString();
                row.put(
                        "candidate",
                        configuration.has("dataflowAssignment")
                                ? "DATAFLOW_BINDING_V1"
                                : "INVALID".equals(planner)
                                        ? configuration
                                                .get("schedulingAlgorithm")
                                                .getAsString()
                                        : planner);
                row.put("seed", configuration.get("rootSeed").getAsBigDecimal().longValueExact());
                row.put(
                        "status",
                        manifest.getAsJsonObject("result")
                                .get("logicalTaskCompletionStatus")
                                .getAsString());
                evidence.attachTo(row);
                HtmlReports.experiment(
                        "Single simulation experiment", java.util.Collections.singletonList(row), target);
                return;
            default:
                throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    public static Path run(Path configPath, Path outputRoot) throws Exception {
        WorkbenchConfig config = WorkbenchConfig.read(configPath);
        config.validateInputs();
        Path root = outputRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        String id = "experiment-" + UUID.randomUUID().toString();
        Path directory = root.resolve(id);
        Files.createDirectory(directory);
        ExperimentArtifactWriter.writeJson(directory.resolve("configuration.json"), config.getSource());
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> display = new ArrayList<Map<String, Object>>();
        Map<String, Object> experiment = new LinkedHashMap<String, Object>();
        experiment.put("schema", "workflowsim-workbench-experiment-v1");
        experiment.put("id", id);
        experiment.put("name", config.getName());
        experiment.put("startedAt", Instant.now().toString());
        experiment.put("plannedRuns", config.getRunCount());
        experiment.put("status", "RUNNING");
        experiment.put("runs", rows);
        ExperimentArtifactWriter.writeJson(directory.resolve("experiment.json"), experiment);
        boolean disabled = Log.isDisabled();
        Log.disable();
        try {
            Path datasetRoot = configPath.toAbsolutePath().getParent();
            ExperimentEvidenceContext context = ExperimentEvidenceContext.builder("workbench-experiment")
                    .artifact("org.workflowsim", "workbench", "1.0")
                    .driver(Workbench.class)
                    .protocol("workbench-configuration-v1", directory.resolve("configuration.json"))
                    .datasetRoot(datasetRoot)
                    .build();
            for (WorkbenchConfig.Candidate candidate : config.getCandidates()) {
                for (long seed : config.getSeeds()) {
                    RunOutcome outcome = executeCandidateRun(candidate, seed, config, directory, context);
                    rows.add(outcome.row);
                    display.add(outcome.reportRow);
                    ExperimentArtifactWriter.writeJson(directory.resolve("experiment.json"), experiment);
                }
            }
        } finally {
            Log.setDisabled(disabled);
            finalizeExperiment(config, rows, display, experiment, directory, root);
        }
        return directory;
    }

    private static RunOutcome executeCandidateRun(
            WorkbenchConfig.Candidate candidate,
            long seed,
            WorkbenchConfig config,
            Path directory,
            ExperimentEvidenceContext context)
            throws Exception {
        String runId = candidate.id + "-s" + seed;
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("candidate", candidate.id);
        row.put("algorithm", candidate.algorithmId);
        List<String> workflowPaths = candidate.config.getWorkflowPaths();
        row.put(
                "workflow",
                workflowPaths.size() == 1
                        ? Paths.get(workflowPaths.get(0)).getFileName().toString()
                        : "combined");
        row.put("seed", seed);
        Map<String, Object> reportRow = new LinkedHashMap<String, Object>(row);
        try {
            SimulationReport report =
                    new SimulationRunner().run(candidate.config.withRandomSeed(seed), config.getPlatform());
            HtmlReports.ValidatedReport evidence = null;
            if (config.getEvidenceOutput().requiresPerRunArtifacts()) {
                ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(
                        report, directory.resolve("runs").resolve(runId), "result", context);
                evidence = HtmlReports.validatedReport(artifacts.getManifest());
                row.put(
                        "manifest",
                        directory.relativize(artifacts.getManifest()).toString().replace('\\', '/'));
            }
            String status = report.isWorkflowCompletedSuccessfully() ? "COMPLETED_SUCCESSFULLY" : "INCOMPLETE";
            row.put("status", status);

            row.put("makespanSeconds", report.getMakespan());
            org.workflowsim.experiment.SimulationMetrics metrics = report.getMetrics();
            row.put("meanWaitingSeconds", metrics.getMeanComputeTotalWaitingTimeSeconds());
            row.put("meanTrueSlowdown", metrics.getMeanComputeTrueSlowdown());

            row.put("boundedSlowdownThresholdSeconds", metrics.getBoundedSlowdownThresholdSeconds());
            row.put("boundedSlowdownFloorClampedFraction", metrics.getBoundedSlowdownFloorClampedFraction());
            row.put("meanReadyToDecisionSeconds", metrics.getMeanComputeReadyToDecisionDelaySeconds());
            row.put("meanDecisionToStartSeconds", metrics.getMeanComputeDecisionToStartDelaySeconds());

            row.put("meanRunTimeSeconds", metrics.getMeanComputeJobRunTimeSeconds());
            row.put("throughputJobsPerSecond", metrics.getComputeJobOutcomeThroughputPerSecond());
            row.put("meanVmUtilization", metrics.getMeanVmModeledIntervalUtilization());

            row.put("inputReadShareOfBusyTime", metrics.getInputReadShareOfBusyTime());
            row.put("energyJoules", report.getEnergyAccounting().getTotalEnergyJoules());
            row.put("energyDelayProduct", report.getEnergyAccounting().getEnergyMakespanProductJouleSeconds());
            row.put("modeledProcessingCost", metrics.getTotalModeledProcessingCost());
            row.put("logicalTasks", metrics.getLogicalTaskCount());
            row.put("completedTasks", metrics.getSuccessfullyCompletedLogicalTaskCount());
            reportRow.put("status", status);
            if (evidence != null) {
                evidence.attachTo(reportRow);
            }
        } catch (Exception failure) {
            String message = message(failure);
            row.put("status", "FAILED");
            row.put("error", message);
            reportRow.put("status", "FAILED");
            reportRow.put("error", message);
        }
        return new RunOutcome(row, reportRow);
    }

    private static void finalizeExperiment(
            WorkbenchConfig config,
            List<Map<String, Object>> rows,
            List<Map<String, Object>> display,
            Map<String, Object> experiment,
            Path directory,
            Path root)
            throws IOException {
        int successful = 0;
        for (Map<String, Object> row : rows) {
            if ("COMPLETED_SUCCESSFULLY".equals(row.get("status"))) {
                successful++;
            }
        }
        experiment.put("successfulRuns", successful);
        experiment.put(
                "status",
                rows.size() == config.getRunCount() && successful == rows.size()
                        ? "COMPLETED_SUCCESSFULLY"
                        : "FAILED_OR_INCOMPLETE");
        experiment.put("finishedAt", Instant.now().toString());
        ExperimentArtifactWriter.writeJson(directory.resolve("experiment.json"), experiment);
        ExperimentSummaryWriter.write(config.getName(), rows, directory);
        HtmlReports.experiment(config.getName(), display, directory.resolve("report.html"));
        history(root);
    }

    private static final class RunOutcome {
        final Map<String, Object> row, reportRow;

        RunOutcome(Map<String, Object> row, Map<String, Object> reportRow) {
            this.row = row;
            this.reportRow = reportRow;
        }
    }

    public static Path history(Path rootPath) throws IOException {
        Path root = rootPath.toAbsolutePath().normalize();
        Files.createDirectories(root);
        List<Path> directories = new ArrayList<Path>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(p -> !Files.isSymbolicLink(p)
                            && Files.isDirectory(p)
                            && p.getFileName().toString().startsWith("experiment-"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(directories::add);
        }
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        StringBuilder html =
                new StringBuilder("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" "
                        + "content=\"width=device-width, initial-scale=1\"><title>WorkflowSim "
                        + "Experiment history</title><style>body{font:16px/1.7 system-ui;background:#f3f6f8;"
                        + "color:#142b40;max-"
                        + "width:1100px;margin:45px auto;padding:20px}table{background:white;width:100%;border-c"
                        + "ollapse:collapse}td,th{padding:15px;text-align:left;border-bottom:1px solid "
                        + "#dce5ea}a{color:#087f8c}.hint{color:#536b7c}</style><h1>Experiment history</h1><p "
                        + "class=\"hint\">Each experiment is retained independently. A running status may also "
                        + "mean the process was interrupted; check that experiment record.</p><table><tr>"
                        + "<th>Experiment</th><th>Status</th"
                        + "><th>Successful / planned</th><th>Started at</th></tr>");
        for (Path directory : directories) {
            Map<String, Object> record = new LinkedHashMap<String, Object>();
            String relative = directory.getFileName().toString();
            try {
                JsonObject experiment = read(directory.resolve("experiment.json"));
                String name = experiment.get("name").getAsString();
                String status = experiment.get("status").getAsString();
                String startedAt = experiment.get("startedAt").getAsString();
                int plannedRuns = experiment.get("plannedRuns").getAsInt();
                record.put("directory", relative);
                record.put("name", name);
                record.put("status", status);
                int successful = experiment.has("successfulRuns")
                        ? experiment.get("successfulRuns").getAsInt()
                        : 0;
                record.put("successfulRuns", successful);
                record.put("plannedRuns", experiment.get("plannedRuns").getAsInt());
                html.append("<tr><td>");
                if (Files.isRegularFile(directory.resolve("report.html"))) {
                    html.append("<a href=\"")
                            .append(HtmlReports.escape(relative))
                            .append("/report.html\">")
                            .append(HtmlReports.escape(name))
                            .append("</a>");
                } else {
                    html.append(HtmlReports.escape(name));
                }
                html.append("</td><td>")
                        .append(HtmlReports.escape(status))
                        .append("</td><td>")
                        .append(successful)
                        .append(" / ")
                        .append(plannedRuns)
                        .append("</td><td>")
                        .append(HtmlReports.escape(startedAt))
                        .append("</td></tr>");
            } catch (RuntimeException | IOException invalid) {
                record.put("directory", relative);
                record.put("status", "INVALID_RECORD");
                record.put("error", invalid.getMessage());
                html.append("<tr><td>")
                        .append(HtmlReports.escape(relative))
                        .append("</td><td>Record damaged</td><td>-</td><td>-</td></tr>");
            }
            records.add(record);
        }
        ExperimentArtifactWriter.writeJson(root.resolve("history.json"), records);
        HtmlReports.writeAtomic(
                root.resolve("index.html"), html.append("</table></html>").toString());
        return root.resolve("index.html");
    }

    private static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static void requireLength(String[] args, int length) {
        if (args.length != length) {
            throw new IllegalArgumentException("Incorrect argument count for " + args[0]);
        }
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
