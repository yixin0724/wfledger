package org.workflowsim.experiments.reference.p7;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.cloudbus.cloudsim.Log;
import org.jdom2.Document;
import org.jdom2.input.SAXBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.workflowsim.Task;
import org.workflowsim.WorkflowDagValidator;
import org.workflowsim.WorkflowInputReport;
import org.workflowsim.WorkflowParser;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/** Independent XML job-count and DAG checks for all 20 retained classic DAX inputs (B04, 2026-11-04). */
class ClassicDaxStructuralTest {

    @BeforeEach
    void quiet() {
        Log.disable();
    }

    @AfterEach
    void restore() {
        Log.enable();
    }

    static Stream<Path> classicDaxInputs() throws Exception {
        Path root = datasetRoot().resolve("dax");
        List<Path> inputs = new ArrayList<Path>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".dax"))
                    .filter(path -> !path.startsWith(root.resolve("heft")))
                    .sorted()
                    .forEach(inputs::add);
        }
        if (inputs.size() != 20) {
            throw new IllegalStateException("Expected 20 classic DAX inputs, found " + inputs.size());
        }
        return inputs.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("classicDaxInputs")
    void parsesEveryClassicDagAgainstIndependentXmlCount(Path input) throws Exception {
        Document document = new SAXBuilder().build(input.toFile());
        int expectedTasks = document.getRootElement()
                .getChildren("job", document.getRootElement().getNamespace())
                .size();
        try (SimulationSession session = SimulationSession.open(
                SimulationConfig.builder(input.toString(), 1).build())) {
            WorkflowParser parser = new WorkflowParser(0);
            parser.parse();
            List<Task> tasks = parser.getTaskList();
            assertEquals(expectedTasks, tasks.size(), "DAX job count for " + input);
            assertEquals(1, parser.getInputReports().size());
            assertEquals(
                    WorkflowInputReport.Format.DAX_XML,
                    parser.getInputReports().get(0).getFormat());
            WorkflowDagValidator.validateAndAssignDepths(tasks);
        }
    }

    private static Path datasetRoot() {
        return Paths.get(System.getProperty("workflowsim.datasetRoot", "../datasets"))
                .toAbsolutePath()
                .normalize();
    }
}
