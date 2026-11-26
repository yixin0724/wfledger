package org.workflowsim.experiments.fgcs.verification;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

final class AdversarialBundle {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private final Path directory;
    private final Path manifestPath;
    private final JsonObject manifest;

    private AdversarialBundle(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.manifestPath = this.directory.resolve("result.manifest.json");
        this.manifest = readJson(manifestPath);
    }

    static AdversarialBundle open(Path directory) throws IOException {
        return new AdversarialBundle(directory);
    }

    static JsonObject readJson(Path path) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    static void copyDirectory(Path source, Path target) throws IOException {
        Path origin = source.toAbsolutePath().normalize();
        Path destination = target.toAbsolutePath().normalize();
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(origin)) {
            paths = walk.sorted().collect(Collectors.toList());
        }
        for (Path path : paths) {
            Path relative = origin.relativize(path);
            Path output = destination.resolve(relative);
            if (Files.isDirectory(path)) {
                Files.createDirectories(output);
            } else {
                Files.createDirectories(output.getParent());
                Files.copy(path, output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    JsonObject manifest() {
        return manifest;
    }

    Path manifestPath() {
        return manifestPath;
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
            for (int index = 0; index < events.size(); index++) {
                JsonObject event = events.get(index);
                event.addProperty("sequence", index);
                writer.write(JSON.toJson(event));
                writer.newLine();
            }
        }
        JsonObject summary = manifest.getAsJsonObject("events");
        summary.addProperty("eventCount", events.size());
        summary.addProperty("firstSequence", 0L);
        summary.addProperty("lastSequence", events.size() - 1L);
    }

    JsonObject readLifecycle() throws IOException {
        return readJson(rolePath(lifecycleRole()));
    }

    void writeLifecycle(JsonObject lifecycle) throws IOException {
        JsonArray events = lifecycle.getAsJsonArray("events");
        for (int index = 0; index < events.size(); index++) {
            events.get(index).getAsJsonObject().addProperty("sequence", index + 1L);
        }
        lifecycle.getAsJsonObject("capture").addProperty("retainedRecords", events.size());
        writeJson(rolePath(lifecycleRole()), lifecycle);
    }

    void commit() throws IOException {
        for (JsonElement element : manifest.getAsJsonArray("artifacts")) {
            JsonObject artifact = element.getAsJsonObject();
            Path path = directory.resolve(artifact.get("path").getAsString()).normalize();
            artifact.addProperty("sha256", sha256(path));
            artifact.addProperty("sizeBytes", Files.size(path));
        }
        writeJson(manifestPath, manifest);
    }

    private String lifecycleRole() {
        String kind = manifest.getAsJsonObject("configuration")
                .getAsJsonObject("dataMovementModel")
                .get("kind")
                .getAsString();
        return kind.contains("STORAGE") ? "storage-lifecycle" : "file-lifecycle";
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

    private static String sha256(Path path) throws IOException {
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

    private static void writeJson(Path path, JsonObject value) throws IOException {
        Files.write(path, JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }
}
