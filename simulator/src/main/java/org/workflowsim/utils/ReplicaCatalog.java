/*
 *
 *   Copyright 2012-2013 University Of Southern California
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.workflowsim.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.FileItem;

/**
 * Per-simulation file replica catalog.
 *
 * <p>The catalog holds file metadata and the logical storage locations that hold each file.
 * It is global runtime state reset by {@link SimulationSession}, must not be reused across
 * sessions, and does not represent the consistency or replication protocol of an external
 * storage system.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class ReplicaCatalog {

    /** Supported file system abstraction. */
    public enum FileSystem {
        SHARED,
        LOCAL
    }
    /** File name to file metadata mapping. */
    private static Map<String, FileItem> fileName2File;
    /** File system model selected for the current session. */
    private static FileSystem fileSystem;
    /** File name to the list of logical storage locations holding that file. */
    private static Map<String, List<String>> dataReplicaCatalog;

    /**
     * Initializes the replica catalog for this simulation.
     *
     * @param fs shared or local file system model
     */
    public static void init(FileSystem fs) {
        if (fs == null) {
            throw new IllegalArgumentException("Replica catalog file system cannot be null");
        }
        fileSystem = fs;
        dataReplicaCatalog = new HashMap<>();
        fileName2File = new HashMap<>();
    }

    /** Clears all replica state of this run. */
    public static void reset() {
        fileSystem = null;
        dataReplicaCatalog = null;
        fileName2File = null;
    }

    private static void requireInitialized() {
        if (fileSystem == null || dataReplicaCatalog == null || fileName2File == null) {
            throw new IllegalStateException("ReplicaCatalog is not initialized for this simulation run");
        }
    }

    /** @return current file system model */
    public static FileSystem getFileSystem() {
        requireInitialized();
        return fileSystem;
    }

    /**
     * Looks up file metadata by file name.
     *
     * @param fileName file name
     * @return file metadata, or {@code null} if absent from the catalog
     */
    public static FileItem getFile(String fileName) {
        requireInitialized();
        return fileName2File.get(fileName);
    }

    /**
     * Stores a file name and its file metadata.
     *
     * @param fileName file name
     * @param file file metadata
     */
    public static void setFile(String fileName, FileItem file) {
        requireInitialized();
        fileName2File.put(fileName, file);
    }

    /**
     * Tests whether the catalog contains the given file.
     *
     * @param fileName file name
     * @return {@code true} if present
     */
    public static boolean containsFile(String fileName) {
        requireInitialized();
        return fileName2File.containsKey(fileName);
    }

    /**
     * Returns the logical storage locations that hold the given file.
     *
     * @param file file name
     * @return list of storage locations, or {@code null} if no location is registered yet
     */
    public static List<String> getStorageList(String file) {
        requireInitialized();
        return dataReplicaCatalog.get(file);
    }

    /**
     * Registers a file at one logical storage location.
     *
     * @param file file name
     * @param storage storage location holding that file
     */
    public static void addFileToStorage(String file, String storage) {
        requireInitialized();
        if (!dataReplicaCatalog.containsKey(file)) {
            dataReplicaCatalog.put(file, new ArrayList<>());
        }
        List<String> list = getStorageList(file);
        if (!list.contains(storage)) {
            list.add(storage);
        }
    }
}
