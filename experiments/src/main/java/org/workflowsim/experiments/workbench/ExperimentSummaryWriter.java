package org.workflowsim.experiments.workbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.experiment.ExperimentArtifactWriter;

public final class ExperimentSummaryWriter {

    private static final String[] COLUMNS = {
        "workflow",
        "algorithm",
        "candidate",
        "seed",
        "status",
        "makespanSeconds",
        "meanWaitingSeconds",
        "meanTrueSlowdown",
        "boundedSlowdownThresholdSeconds",
        "boundedSlowdownFloorClampedFraction",
        "meanReadyToDecisionSeconds",
        "meanDecisionToStartSeconds",
        "meanRunTimeSeconds",
        "throughputJobsPerSecond",
        "meanVmUtilization",
        "inputReadShareOfBusyTime",
        "energyJoules",
        "energyDelayProduct",
        "modeledProcessingCost",
        "logicalTasks",
        "completedTasks",
        "manifest",
        "error"
    };

    private ExperimentSummaryWriter() {}

    public static void write(String name, List<Map<String, Object>> rows, Path directory) throws IOException {
        List<Map<String, Object>> sorted = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            sorted.add(new LinkedHashMap<String, Object>(row));
        }
        sorted.sort(Comparator.comparing(ExperimentSummaryWriter::text)
                .thenComparing(ExperimentSummaryWriter::algorithm)
                .thenComparingLong(ExperimentSummaryWriter::seed));
        int successful = 0;
        for (Map<String, Object> row : sorted) {
            if ("COMPLETED_SUCCESSFULLY".equals(row.get("status"))) {
                successful++;
            }
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schema", "workflowsim-experiment-summary-v1");
        root.put("name", name);
        root.put("runCount", sorted.size());
        root.put("successfulRuns", successful);
        root.put("runs", sorted);
        ExperimentArtifactWriter.writeJson(directory.resolve("summary.json"), root);
        StringBuilder csv = new StringBuilder(String.join(",", COLUMNS)).append('\n');
        for (Map<String, Object> row : sorted) {
            for (int index = 0; index < COLUMNS.length; index++) {
                if (index > 0) {
                    csv.append(',');
                }
                csv.append(escape(row.get(COLUMNS[index])));
            }
            csv.append('\n');
        }
        Files.write(directory.resolve("summary.csv"), csv.toString().getBytes(StandardCharsets.UTF_8));
        writeHtml(name, sorted, directory.resolve("summary.html"));
    }

    private static void writeHtml(String name, List<Map<String, Object>> rows, Path target) throws IOException {
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\">"
                        + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                        + "<title>Experiment summary</title><style>body{font:15px/1.5 system-ui;max-width:1600px;"
                        + "margin:32px auto;padding:0 16px;color:#162b3a}table{border-collapse:collapse;width:100%;"
                        + "background:white}th,td{padding:8px;border-bottom:1px solid #dce5ea;text-align:right}"
                        + "th:first-child,td:first-child,th:nth-child(2),td:nth-child(2){text-align:left}"
                        + ".FAILED,.INCOMPLETE{color:#a22}.COMPLETED_SUCCESSFULLY{color:#164}</style><h1>")
                .append(html(name))
                .append("</h1><table><tr><th>Workflow</th><th>Algorithm</th><th>Candidate</th>"
                        + "<th>Seed</th><th>Status</th><th>Makespan (s)</th><th>Mean wait (s)</th>"
                        + "<th>Bounded slowdown (floor s)</th><th>Energy (J)</th><th>EDP (J·s)</th>"
                        + "<th>Cost</th><th>Tasks</th></tr>");
        for (Map<String, Object> row : rows) {
            html.append("<tr><td>")
                    .append(html(row.get("workflow")))
                    .append("</td><td>")
                    .append(html(row.get("algorithm")))
                    .append("</td><td>")
                    .append(html(row.get("candidate")))
                    .append("</td><td>")
                    .append(html(row.get("seed")))
                    .append("</td><td class=\"")
                    .append(html(row.get("status")))
                    .append("\">")
                    .append(html(row.get("status")))
                    .append("</td><td>")
                    .append(html(row.get("makespanSeconds")))
                    .append("</td><td>")
                    .append(html(row.get("meanWaitingSeconds")))
                    .append("</td><td>")
                    .append(html(row.get("meanTrueSlowdown")))
                    .append(" (")
                    .append(html(row.get("boundedSlowdownThresholdSeconds")))
                    .append(")</td><td>")
                    .append(html(row.get("energyJoules")))
                    .append("</td><td>")
                    .append(html(row.get("energyDelayProduct")))
                    .append("</td><td>")
                    .append(html(row.get("modeledProcessingCost")))
                    .append("</td><td>")
                    .append(html(row.get("completedTasks")))
                    .append('/')
                    .append(html(row.get("logicalTasks")))
                    .append("</td></tr>");
        }
        html.append("</table><p><a href=\"summary.json\">JSON</a> · <a href=\"summary.csv\">CSV</a> · "
                + "<a href=\"report.html\">Detailed report</a></p></html>");
        Files.write(target, html.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String html(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static String text(Map<String, Object> row) {
        Object value = row.get("workflow");
        return value == null ? "" : String.valueOf(value);
    }

    private static String algorithm(Map<String, Object> row) {
        Object value = row.get("algorithm");
        return value == null ? String.valueOf(row.get("candidate")) : String.valueOf(value);
    }

    private static long seed(Map<String, Object> row) {
        Object value = row.get("seed");
        return value instanceof Number ? ((Number) value).longValue() : Long.MAX_VALUE;
    }
}
