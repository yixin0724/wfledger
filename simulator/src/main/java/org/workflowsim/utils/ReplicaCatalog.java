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

public class ReplicaCatalog {

    public enum FileSystem {
        SHARED,
        LOCAL
    }

    private static Map<String, FileItem> fileName2File;

    private static FileSystem fileSystem;

    private static Map<String, List<String>> dataReplicaCatalog;

    public static void init(FileSystem fs) {
        if (fs == null) {
            throw new IllegalArgumentException("Replica catalog file system cannot be null");
        }
        fileSystem = fs;
        dataReplicaCatalog = new HashMap<>();
        fileName2File = new HashMap<>();
    }

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

    public static FileSystem getFileSystem() {
        requireInitialized();
        return fileSystem;
    }

    public static FileItem getFile(String fileName) {
        requireInitialized();
        return fileName2File.get(fileName);
    }

    public static void setFile(String fileName, FileItem file) {
        requireInitialized();
        fileName2File.put(fileName, file);
    }

    public static boolean containsFile(String fileName) {
        requireInitialized();
        return fileName2File.containsKey(fileName);
    }

    public static List<String> getStorageList(String file) {
        requireInitialized();
        return dataReplicaCatalog.get(file);
    }

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
