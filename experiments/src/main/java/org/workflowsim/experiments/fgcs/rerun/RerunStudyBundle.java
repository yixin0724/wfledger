package org.workflowsim.experiments.fgcs.rerun;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** File-level support for real manifest-v4 bundles; it never derives an expected verdict from an executor. */
final class RerunStudyBundle {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private final Path directory;
    private final Path manifestPath;
    private final JsonObject manifest;

    private RerunStudyBundle(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.manifestPath = this.directory.resolve("result.manifest.json");
        this.manifest = readJson(this.manifestPath);
    }

    static RerunStudyBundle open(Path directory) throws IOException {
        return new RerunStudyBundle(directory);
    }

    static RerunStudyBundle copy(Path source, Path target) throws IOException {
        copyDirectory(source, target);
        return open(target);
    }

    static void copyDirectory(Path source, Path target) throws IOException {
        Path origin = source.toAbsolutePath().normalize();
        Path destination = target.toAbsolutePath().normalize();
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(origin)) {
            paths = walk.sorted().collect(Collectors.toList());
        }
        for (Path path : paths) {
            Path output = destination.resolve(origin.relativize(path));
            if (Files.isDirectory(path)) {
                Files.createDirectories(output);
            } else {
                Files.createDirectories(output.getParent());
                Files.copy(path, output);
            }
        }
    }

    Path directory() {
        return directory;
    }

    JsonObject manifest() {
        return manifest;
    }

    void writeManifest() throws IOException {
        writeJson(manifestPath, manifest);
    }

    List<JsonObject> readMainEvents() throws IOException {
        List<JsonObject> events = new ArrayList<JsonObject>();
        for (String line : Files.readAllLines(rolePath("events"), StandardCharsets.UTF_8)) {
            if (!line.trim().isEmpty()) {
                events.add(JsonParser.parseString(line).getAsJsonObject());
            }
        }
        return events;
    }

    void writeMainEvents(List<JsonObject> events) throws IOException {
        Path path = rolePath("events");
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (JsonObject event : events) {
                writer.write(JSON.toJson(event));
                writer.newLine();
            }
        }
    }

    /** Repairs integrity declarations only after the semantic mutation; it does not make invalid semantics valid. */
    void commitArtifactDigests() throws IOException {
        for (JsonElement element : manifest.getAsJsonArray("artifacts")) {
            JsonObject artifact = element.getAsJsonObject();
            Path path = directory.resolve(artifact.get("path").getAsString()).normalize();
            artifact.addProperty("sha256", sha256(path));
            artifact.addProperty("sizeBytes", Files.size(path));
        }
        writeManifest();
    }

    JsonObject evidenceHashes() throws IOException {
        return evidenceHashes(directory);
    }

    static JsonObject evidenceHashes(Path bundle) throws IOException {
        Path directory = bundle.toAbsolutePath().normalize();
        Path manifestPath = directory.resolve("result.manifest.json");
        JsonObject manifest = readJson(manifestPath);
        JsonObject value = new JsonObject();
        value.addProperty("directory", directory.toString());
        value.add("manifest", fileHash(manifestPath));
        JsonArray artifacts = new JsonArray();
        boolean allIntegrityDeclarationsMatch = true;
        for (JsonElement element : manifest.getAsJsonArray("artifacts")) {
            JsonObject declaration = element.getAsJsonObject();
            String role = declaration.get("role").getAsString();
            String relative = declaration.get("path").getAsString();
            Path path = directory.resolve(relative).normalize();
            JsonObject artifact = fileHash(path);
            artifact.addProperty("role", role);
            artifact.addProperty("referencePath", relative);
            artifact.addProperty("declaredSha256", declaration.get("sha256").getAsString());
            artifact.addProperty(
                    "declaredSizeBytes", declaration.get("sizeBytes").getAsLong());
            boolean matches = declaration
                            .get("sha256")
                            .getAsString()
                            .equals(artifact.get("sha256").getAsString())
                    && declaration.get("sizeBytes").getAsLong()
                            == artifact.get("sizeBytes").getAsLong();
            artifact.addProperty("integrityDeclarationMatches", matches);
            allIntegrityDeclarationsMatch &= matches;
            artifacts.add(artifact);
        }
        value.add("artifacts", artifacts);
        value.addProperty("allArtifactIntegrityDeclarationsMatch", allIntegrityDeclarationsMatch);
        JsonArray inputs = new JsonArray();
        for (JsonElement element : manifest.getAsJsonArray("inputs")) {
            JsonObject input = element.getAsJsonObject();
            JsonObject identity = new JsonObject();
            identity.addProperty("recordedPath", input.get("path").getAsString());
            identity.addProperty("declaredSha256", input.get("sha256").getAsString());
            identity.addProperty("declaredSizeBytes", input.get("sizeBytes").getAsLong());
            identity.addProperty("format", input.get("format").getAsString());
            inputs.add(identity);
        }
        value.add("declaredInputIdentities", inputs);
        return value;
    }

    static JsonObject fileHash(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        JsonObject value = new JsonObject();
        value.addProperty("path", absolute.toString());
        value.addProperty("sha256", sha256(absolute));
        value.addProperty("sizeBytes", Files.size(absolute));
        return value;
    }

    static String sha256(Path path) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest) {
                value.append(String.format("%02x", item & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    static JsonObject readJson(Path path) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    static void writeJson(Path path, JsonObject value) throws IOException {
        Files.write(path, JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    private Path rolePath(String role) throws IOException {
        for (JsonElement element : manifest.getAsJsonArray("artifacts")) {
            JsonObject artifact = element.getAsJsonObject();
            if (role.equals(artifact.get("role").getAsString())) {
                return directory.resolve(artifact.get("path").getAsString()).normalize();
            }
        }
        throw new IOException("Missing artifact role " + role + " in " + manifestPath);
    }
}
