#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = (
    "LICENSE",
    "CITATION.cff",
    "README.md",
    "NOTICE.md",
    "THIRD_PARTY.md",
    "simulator/src/main/java/org/workflowsim/WorkflowPlanner.java",
    "simulator/src/main/java/org/cloudbus/cloudsim/Cloudlet.java",
    "licenses/README.md",
    "licenses/provenance.json",
    "licenses/Apache-2.0.txt",
    "licenses/CloudSim-LGPL-3.0.txt",
    "licenses/WorkflowSim-GTPL-notice.txt",
    "licenses/GTPL-3.0.txt",
    "licenses/GPL-3.0.txt",
)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check(root):
    root = Path(root)
    issues = []
    for relative in REQUIRED:
        if not (root / relative).is_file():
            issues.append("missing " + relative)
    if issues:
        return issues
    provenance = json.loads((root / "licenses/provenance.json").read_text(encoding="utf-8"))
    if provenance.get("schema") != "wfledger-license-provenance-v1":
        issues.append("unexpected provenance schema")
    if provenance.get("projectLicense") != "GPL-3.0-or-later":
        issues.append("project license is not GPL-3.0-or-later")
    if provenance.get("upstream", {}).get("commit") != "d3ea21afd8ce6479bd292d3bd7469045d7a36089":
        issues.append("upstream commit is not pinned")
    for name, record in provenance.get("files", {}).items():
        path = root / "licenses" / name
        if not path.is_file():
            issues.append("missing provenance file licenses/" + name)
            continue
        if record.get("sha256") != digest(path):
            issues.append("license hash mismatch for " + name)
        if record.get("sizeBytes") != path.stat().st_size:
            issues.append("license size mismatch for " + name)
    if (root / "LICENSE").read_bytes() != (root / "licenses/GPL-3.0.txt").read_bytes():
        issues.append("root LICENSE differs from bundled GPL-3.0 text")
    metadata = (root / "CITATION.cff").read_text(encoding="utf-8")
    if 'license: "GPL-3.0-or-later"' not in metadata:
        issues.append("CITATION license metadata is not GPL-3.0-or-later")
    if "GPL-2.0-or-later" in metadata:
        issues.append("stale GPL-2.0-or-later metadata")
    notice = (root / "NOTICE.md").read_text(encoding="utf-8").lower()
    for phrase in ("university of southern california", "university of melbourne", "gtpl", "modifications", "2026"):
        if phrase not in notice:
            issues.append("NOTICE missing " + phrase)
    third_party = (root / "THIRD_PARTY.md").read_text(encoding="utf-8")
    for phrase in ("193 Java files", "59 carry Apache-2.0 headers", "GTPL", "d3ea21afd8ce"):
        if phrase not in third_party:
            issues.append("THIRD_PARTY missing " + phrase)
    planner = (root / "simulator/src/main/java/org/workflowsim/WorkflowPlanner.java").read_text(encoding="utf-8")
    cloudlet = (root / "simulator/src/main/java/org/cloudbus/cloudsim/Cloudlet.java").read_text(encoding="utf-8")
    if "Copyright 2012-2013 University Of Southern California" not in planner or "Apache License, Version 2.0" not in planner:
        issues.append("WorkflowSim copyright/license header changed unexpectedly")
    if "Copyright (c) 2009-2012, The University of Melbourne, Australia" not in cloudlet or "GPL" not in cloudlet:
        issues.append("CloudSim copyright/license header changed unexpectedly")
    for relative in ("README.md", "NOTICE.md", "THIRD_PARTY.md", "CITATION.cff"):
        if "WorkflowSim-yixin" in (root / relative).read_text(encoding="utf-8"):
            issues.append("stale product name in " + relative)
    return issues


def main():
    issues = check(ROOT)
    if issues:
        for issue in issues:
            print("LICENSE_COMPLIANCE_ERROR", issue)
        return 1
    print("LICENSE_COMPLIANCE_OK files={} upstream=d3ea21afd8ce".format(len(REQUIRED)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
