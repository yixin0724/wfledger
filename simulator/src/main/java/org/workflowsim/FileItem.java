/**
 * Copyright 2014-2015 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim;

import java.util.List;

import org.workflowsim.utils.Parameters.FileType;

/**
 * Workflow file descriptor for WorkflowSim.
 *
 * <p>CloudSim already provides {@code File}, but workflow data dependencies also need to
 * store the file size as a {@code double} and distinguish input, output, and intermediate
 * file semantics, so a dedicated {@code FileItem} is used. The file-size unit is fixed by
 * the input format; both the WfCommons and DAX paths pass bytes.</p>
 *
 * @author weiweich
 */
public class FileItem {

    private String name;

    private double size;

    private FileType type;

    public FileItem(String name, double size) {
        this.name = name;
        this.size = size;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setSize(double size) {
        this.size = size;
    }

    public void setType(FileType type) {
        this.type = type;
    }

    public String getName() {
        return this.name;
    }

    public double getSize() {
        return this.size;
    }

    public FileType getType() {
        return this.type;
    }

    /**
     * Determines whether an input file requires stage-in from outside the job.
     *
     * <p>Workflows follow the convention that a file is written once and may be read many
     * times. If a same-named output file exists within the same job, the input is produced
     * by a task in that job and must not be counted as stage-in again. This check mainly
     * serves file-transfer estimation after horizontal clustering.</p>
     *
     * @param list all files associated with the current job
     * @return {@code true} when this object is a real external input that has no same-named
     *         in-job output file
     */
    public boolean isRealInputFile(List<FileItem> list) {
        if (this.getType() == FileType.INPUT) {
            for (FileItem another : list) {
                // A same-named output means the data is produced within this job, so no extra stage-in is needed.
                if (another.getName().equals(this.getName()) && another.getType() == FileType.OUTPUT) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
