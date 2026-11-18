package org.workflowsim.examples;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ExampleCatalog {

    private static final List<Descriptor> LEGACY_EXAMPLES = createLegacyExamples();

    private ExampleCatalog() {}

    public static List<Descriptor> legacyExamples() {
        return LEGACY_EXAMPLES;
    }

    private static List<Descriptor> createLegacyExamples() {
        List<Descriptor> examples = new ArrayList<Descriptor>();
        add(examples, "basic", "org.workflowsim.examples.WorkflowSimBasicExample1");
        add(examples, "dynamic-workload", "org.workflowsim.examples.DynamicWorkloadExample1");
        add(examples, "multiple-cluster", "org.workflowsim.examples.WorkflowSimMultipleClusterExample1");
        add(examples, "multiple-workflows", "org.workflowsim.examples.WorkflowSimMultipleWorkflowsExample1");
        add(examples, "horizontal-clustering-size", "org.workflowsim.examples.clustering.HorizontalClusteringExample1");
        add(
                examples,
                "horizontal-clustering-count",
                "org.workflowsim.examples.clustering.HorizontalClusteringExample2");
        add(
                examples,
                "horizontal-clustering-overhead",
                "org.workflowsim.examples.clustering.HorizontalClusteringExample3");
        add(examples, "vertical-clustering", "org.workflowsim.examples.clustering.VerticalClusteringExample1");
        add(
                examples,
                "balanced-clustering",
                "org.workflowsim.examples.clustering.balancing.BalancedClusteringExample1");
        add(examples, "cost-datacenter", "org.workflowsim.examples.cost.WorkflowSimCostExample1");
        add(examples, "cost-vm", "org.workflowsim.examples.cost.WorkflowSimCostExample2");
        add(examples, "fault-tolerant-scheduling", "org.workflowsim.examples.failure.FaultTolerantSchedulingExample1");
        add(
                examples,
                "fault-clustering-dc",
                "org.workflowsim.examples.failure.clustering.FaultTolerantClusteringExample1");
        add(
                examples,
                "fault-clustering-sr",
                "org.workflowsim.examples.failure.clustering.FaultTolerantClusteringExample2");
        add(
                examples,
                "fault-clustering-dr",
                "org.workflowsim.examples.failure.clustering.FaultTolerantClusteringExample3");
        add(
                examples,
                "fault-clustering-vm",
                "org.workflowsim.examples.failure.clustering.FaultTolerantClusteringExample4");
        add(
                examples,
                "fault-clustering-dynamic",
                "org.workflowsim.examples.failure.clustering.FaultTolerantClusteringExample5");
        add(
                examples,
                "fault-clustering-options",
                "org.workflowsim.examples.failure.clustering.FaultTolerantClusteringExample6");
        add(
                examples,
                "fault-clustering-periodic",
                "org.workflowsim.examples.failure.clustering.FaultTolerantClusteringExample7");
        return Collections.unmodifiableList(examples);
    }

    private static void add(List<Descriptor> examples, String id, String mainClass) {
        examples.add(new Descriptor(id, mainClass));
    }

    public static final class Descriptor {
        private final String id;
        private final String mainClass;

        private Descriptor(String id, String mainClass) {
            this.id = id;
            this.mainClass = mainClass;
        }

        public String getId() {
            return id;
        }

        public String getMainClass() {
            return mainClass;
        }
    }
}
