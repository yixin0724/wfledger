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
