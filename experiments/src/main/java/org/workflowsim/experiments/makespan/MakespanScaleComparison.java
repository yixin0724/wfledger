package org.workflowsim.experiments.makespan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.workflowsim.exception.SimulationExecutionException;

/** Runs the same baseline family at 1000 and exactly 5000 logical tasks. */
public final class MakespanScaleComparison {

    private static final String[] DATASETS = {
        "datasets/dax/montage/n1000/Montage_1000.dax",
        "datasets/dax/cybershake/n1000/CyberShake_1000.dax",
        "datasets/dax/inspiral/n1000/Inspiral_1000.dax"
    };
    private static final Pattern ATTRIBUTE = Pattern.compile("(\\bid|\\bref|\\bfile|\\bname)=\"([^\"]*)\"");

    private MakespanScaleComparison() {}

    public static List<MakespanBaselineComparison.Result> run() throws SimulationExecutionException {
        return run(repositoryRoot(), Paths.get(System.getProperty("java.io.tmpdir"), "makespan-scale-generated"));
    }

    public static List<MakespanBaselineComparison.Result> run(Path repositoryRoot, Path generatedDirectory)
            throws SimulationExecutionException {
        try {
            Files.createDirectories(generatedDirectory);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot create scale-workflow directory " + generatedDirectory, exception);
        }
        List<MakespanBaselineComparison.Result> results = new ArrayList<MakespanBaselineComparison.Result>();
        for (String dataset : DATASETS) {
            Path source = repositoryRoot.resolve(dataset);
            if (!Files.isRegularFile(source)) {
                throw new IllegalArgumentException("Missing scale workflow " + source);
            }
            results.addAll(MakespanBaselineComparison.runDataset(source, dataset + "#1000", 200000));
            Path expanded =
                    generatedDirectory.resolve(source.getFileName().toString().replace(".dax", "-5000.dax"));
            replicate(source, expanded, 5);
            results.addAll(MakespanBaselineComparison.runDataset(expanded, dataset + "#5000", 200000));
        }
        return results;
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? Paths.get("output", "makespan-scale", "results.json") : Paths.get(args[0]);
        List<MakespanBaselineComparison.Result> results = run();
        MakespanBaselineComparison.writeResults(output, results);
        System.out.println("Wrote " + results.size() + " scale results to " + output);
    }

    private static void replicate(Path source, Path target, int copies) {
        try {
            String xml = new String(Files.readAllBytes(source), java.nio.charset.StandardCharsets.UTF_8);
            int open = xml.indexOf("<adag");
            int close = xml.lastIndexOf("</adag>");
            if (open < 0 || close < 0) {
                throw new IllegalArgumentException("Not an ADAG document " + source);
            }
            int bodyStart = xml.indexOf('>', open) + 1;
            String body = xml.substring(bodyStart, close);
            StringBuilder expanded = new StringBuilder(xml.substring(0, bodyStart));
            for (int copy = 0; copy < copies; copy++) {
                String suffix = "_r" + copy;
                Matcher matcher = ATTRIBUTE.matcher(body);
                StringBuffer replaced = new StringBuffer();
                while (matcher.find()) {
                    matcher.appendReplacement(
                            replaced,
                            matcher.group(1) + "=\"" + Matcher.quoteReplacement(matcher.group(2) + suffix) + "\"");
                }
                matcher.appendTail(replaced);
                expanded.append(replaced);
            }
            expanded.append(xml.substring(close));
            String result = expanded.toString().replaceFirst("count=\"[0-9]+\"", "count=\"5000\"");
            Files.write(target, result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot replicate workflow " + source, exception);
        }
    }

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("datasets"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalArgumentException("Cannot locate the WorkflowSim datasets directory");
    }
}
