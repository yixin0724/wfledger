package org.workflowsim.experiments.fgcs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public final class FgcDatasets {

    public static final String MONTAGE_50 = "datasets/dax/montage/n50/Montage_50.dax";
    public static final String CYBERSHAKE_50 = "datasets/dax/cybershake/n50/CyberShake_50.dax";
    public static final String SIPHT_60 = "datasets/dax/sipht/n60/Sipht_60.dax";
    public static final String INSPIRAL_50 = "datasets/dax/inspiral/n50/Inspiral_50.dax";
    public static final String EPIGENOMICS_46 = "datasets/dax/epigenomics/n46/Epigenomics_46.dax";
    public static final String MONTAGE_1000 = "datasets/dax/montage/n1000/Montage_1000.dax";
    public static final String CYBERSHAKE_1000 = "datasets/dax/cybershake/n1000/CyberShake_1000.dax";
    public static final String INSPIRAL_1000 = "datasets/dax/inspiral/n1000/Inspiral_1000.dax";

    private FgcDatasets() {}

    public static String real(String relativePath) {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
            current = current.getParent();
        }
        throw new IllegalArgumentException("Cannot locate FGCS dataset " + relativePath);
    }

    public static Path smallDag(Path directory, String kind) throws IOException {
        Files.createDirectories(directory);
        StringBuilder dax = new StringBuilder("<adag version=\"3.3\" count=\"4\">\n");
        if ("chain".equals(kind)) {
            job(dax, 1, 10.0, "root", 1000000.0, "input");
            job(dax, 2, 20.0, "a", 2000000.0, "output");
            job(dax, 3, 30.0, "b", 3000000.0, "output2");
            job(dax, 4, 40.0, "c", 4000000.0, "output3");
            edge(dax, 2, 1);
            edge(dax, 3, 2);
            edge(dax, 4, 3);
        } else if ("fan".equals(kind)) {
            job(dax, 1, 10.0, "root", 1000000.0, "input");
            job(dax, 2, 20.0, "a", 2000000.0, "a");
            job(dax, 3, 20.0, "b", 3000000.0, "b");
            job(dax, 4, 40.0, "join", 4000000.0, "a", "b");
            edge(dax, 2, 1);
            edge(dax, 3, 1);
            edge(dax, 4, 2, 3);
        } else if ("independent".equals(kind)) {
            job(dax, 1, 10.0, "a", 1000000.0, "a");
            job(dax, 2, 20.0, "b", 2000000.0, "b");
            job(dax, 3, 30.0, "c", 3000000.0, "c");
            job(dax, 4, 40.0, "d", 4000000.0, "d");
        } else {
            throw new IllegalArgumentException("Unknown FGCS DAG shape " + kind);
        }
        dax.append("</adag>\n");
        // R2-DERIVATION: preserve raw bytes before normalization; normalizing the output in place
        // would corrupt source/derived identities and make a stale sidecar appear self-consistent.
        Path rawOutput = directory.resolve("raw-" + kind + ".dax");
        Files.write(rawOutput, dax.toString().getBytes(StandardCharsets.UTF_8));
        Path output = directory.resolve(kind + ".dax");
        return normalizeForStorage(rawOutput, output);
    }

    public static Path normalizeForStorage(String relativePath, Path directory) throws IOException {
        return normalizeForStorage(
                Paths.get(real(relativePath)),
                directory.resolve("unique-producer-" + Paths.get(relativePath).getFileName()));
    }

    public static Path normalizeForStorage(Path source, Path output) throws IOException {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document document = factory.newDocumentBuilder().parse(source.toFile());
            org.w3c.dom.NodeList jobs = document.getElementsByTagName("job");
            Map<String, List<org.w3c.dom.Element>> parents = new LinkedHashMap<String, List<org.w3c.dom.Element>>();
            Map<String, org.w3c.dom.Element> byId = new HashMap<String, org.w3c.dom.Element>();
            for (int index = 0; index < jobs.getLength(); index++) {
                org.w3c.dom.Element job = (org.w3c.dom.Element) jobs.item(index);
                byId.put(job.getAttribute("id"), job);
            }
            org.w3c.dom.NodeList children = document.getElementsByTagName("child");
            int dependencyEdgeCount = 0;
            for (int index = 0; index < children.getLength(); index++) {
                org.w3c.dom.Element child = (org.w3c.dom.Element) children.item(index);
                List<org.w3c.dom.Element> parentJobs = new ArrayList<org.w3c.dom.Element>();
                org.w3c.dom.NodeList refs = child.getElementsByTagName("parent");
                dependencyEdgeCount += refs.getLength();
                for (int parent = 0; parent < refs.getLength(); parent++) {
                    org.w3c.dom.Element parentJob =
                            byId.get(((org.w3c.dom.Element) refs.item(parent)).getAttribute("ref"));
                    if (parentJob == null) {
                        throw new IOException("DAX parent is missing for " + child.getAttribute("ref"));
                    }
                    parentJobs.add(parentJob);
                }
                parents.put(child.getAttribute("ref"), parentJobs);
            }
            Map<org.w3c.dom.Element, Map<String, String>> outputNames =
                    new LinkedHashMap<org.w3c.dom.Element, Map<String, String>>();
            Map<org.w3c.dom.Element, Map<String, String>> outputSizes =
                    new LinkedHashMap<org.w3c.dom.Element, Map<String, String>>();
            Map<String, String> externalInputSizes = new LinkedHashMap<String, String>();
            int renamedOutputs = 0;
            int adjustedInputs = 0;
            int multiProducerInputExpansions = 0;
            int additionalInputDeclarations = 0;
            for (int index = 0; index < jobs.getLength(); index++) {
                org.w3c.dom.Element job = (org.w3c.dom.Element) jobs.item(index);
                Map<String, String> names = new LinkedHashMap<String, String>();
                Map<String, String> sizes = new LinkedHashMap<String, String>();
                for (org.w3c.dom.Element use : directUses(job)) {
                    String name = fileName(use);
                    if ("output".equals(use.getAttribute("link"))) {
                        if (name.isEmpty()) {
                            throw new IOException("Output file has no name in job " + job.getAttribute("id"));
                        }
                        String unique = name + "__producer_" + job.getAttribute("id");
                        if (names.put(name, unique) != null) {
                            throw new IOException(
                                    "Duplicate output within job " + job.getAttribute("id") + ": " + name);
                        }
                        use.setAttribute(use.hasAttribute("file") ? "file" : "name", unique);
                        sizes.put(name, use.getAttribute("size"));
                        renamedOutputs++;
                    } else if ("input".equals(use.getAttribute("link"))) {
                        String first = externalInputSizes.putIfAbsent(name, use.getAttribute("size"));
                        if (first != null && !first.equals(use.getAttribute("size"))) {
                            adjustedInputs++;
                        }
                    }
                }
                outputNames.put(job, names);
                outputSizes.put(job, sizes);
            }
            for (int index = 0; index < jobs.getLength(); index++) {
                org.w3c.dom.Element job = (org.w3c.dom.Element) jobs.item(index);
                for (org.w3c.dom.Element use : directUses(job)) {
                    if (!"input".equals(use.getAttribute("link"))) {
                        continue;
                    }
                    String original = fileName(use);
                    List<org.w3c.dom.Element> matchingParents = new ArrayList<org.w3c.dom.Element>();
                    for (org.w3c.dom.Element parent : parents.getOrDefault(
                            job.getAttribute("id"), java.util.Collections.<org.w3c.dom.Element>emptyList())) {
                        if (outputNames.get(parent).containsKey(original)) {
                            matchingParents.add(parent);
                        }
                    }
                    if (!matchingParents.isEmpty()) {
                        for (int parentIndex = 0; parentIndex < matchingParents.size(); parentIndex++) {
                            org.w3c.dom.Element parent = matchingParents.get(parentIndex);
                            org.w3c.dom.Element input =
                                    parentIndex == 0 ? use : (org.w3c.dom.Element) use.cloneNode(true);
                            input.setAttribute(
                                    input.hasAttribute("file") ? "file" : "name",
                                    outputNames.get(parent).get(original));
                            input.setAttribute("size", outputSizes.get(parent).get(original));
                            if (parentIndex > 0) {
                                job.appendChild(input);
                            }
                            adjustedInputs++;
                        }
                        if (matchingParents.size() > 1) {
                            multiProducerInputExpansions++;
                            additionalInputDeclarations += matchingParents.size() - 1;
                        }
                    } else {
                        String size = externalInputSizes.get(original);
                        if (size == null) {
                            throw new IOException("Input file was not declared before normalization " + original);
                        }
                        use.setAttribute("size", size);
                    }
                }
            }
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            javax.xml.transform.Transformer transformer =
                    javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            transformer.transform(
                    new javax.xml.transform.dom.DOMSource(document),
                    new javax.xml.transform.stream.StreamResult(output.toFile()));
            JsonObject provenance = new JsonObject();
            provenance.addProperty("transformation", "UNIQUE_PRODUCER_FILE_IDENTITY_V1");
            provenance.addProperty("sourcePath", source.toAbsolutePath().toString());
            provenance.addProperty("sourceSha256", sha256(source));
            provenance.addProperty("derivedPath", output.toAbsolutePath().toString());
            provenance.addProperty("derivedSha256", sha256(output));
            provenance.addProperty("jobCount", jobs.getLength());
            provenance.addProperty("dependencyEdgeCount", dependencyEdgeCount);
            provenance.addProperty("renamedOutputDeclarations", renamedOutputs);
            provenance.addProperty("adjustedInputDeclarations", adjustedInputs);
            provenance.addProperty("graphAndRuntimesPreserved", true);
            provenance.addProperty("producerOutputBytesPreserved", true);
            provenance.addProperty("multiProducerInputExpansions", multiProducerInputExpansions);
            provenance.addProperty("additionalInputDeclarations", additionalInputDeclarations);
            provenance.addProperty("externalInputDeclarationRule", "FIRST_DECLARED_SIZE");
            provenance.addProperty("rawInputEquivalenceClaimed", false);
            provenance.addProperty("sourceBytesModified", false);
            Files.write(
                    Paths.get(output.toString() + ".derivation.json"),
                    (new GsonBuilder().setPrettyPrinting().create().toJson(provenance) + "\n")
                            .getBytes(StandardCharsets.UTF_8));
            return output;
        } catch (javax.xml.parsers.ParserConfigurationException
                | org.xml.sax.SAXException
                | javax.xml.transform.TransformerException exception) {
            throw new IOException("Cannot derive storage-consistent input from " + source, exception);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static List<org.w3c.dom.Element> directUses(org.w3c.dom.Element job) {
        List<org.w3c.dom.Element> uses = new ArrayList<org.w3c.dom.Element>();
        org.w3c.dom.NodeList children = job.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof org.w3c.dom.Element
                    && "uses".equals(((org.w3c.dom.Element) children.item(index)).getTagName())) {
                uses.add((org.w3c.dom.Element) children.item(index));
            }
        }
        return uses;
    }

    private static String sha256(Path path) throws IOException, java.security.NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder value = new StringBuilder();
        for (byte item : digest) {
            value.append(String.format("%02x", item & 0xff));
        }
        return value.toString();
    }

    private static String fileName(org.w3c.dom.Element element) {
        return element.hasAttribute("file") ? element.getAttribute("file") : element.getAttribute("name");
    }

    private static void job(StringBuilder dax, int id, double runtime, String suffix, double size, String... inputs) {
        dax.append("<job id=\"j")
                .append(id)
                .append("\" name=\"task")
                .append(suffix)
                .append("\" runtime=\"")
                .append(runtime)
                .append("\">")
                .append("<uses name=\"file")
                .append(suffix)
                .append("\" link=\"output\" size=\"")
                .append(size)
                .append("\"/>");
        for (String input : inputs) {
            dax.append("<uses name=\"")
                    .append(input)
                    .append("\" link=\"input\" size=\"")
                    .append(size)
                    .append("\"/>");
        }
        dax.append("</job>\n");
    }

    private static void edge(StringBuilder dax, int child, int... parents) {
        dax.append("<child ref=\"j").append(child).append("\">");
        for (int parent : parents) {
            dax.append("<parent ref=\"j").append(parent).append("\"/>");
        }
        dax.append("</child>\n");
    }
}
