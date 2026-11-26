#!/usr/bin/env python3

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

INDEX_NAME = "INDEX.json"
SKIP_DIR_NAMES = {".v1.5-git-metadata"}
EXEMPT_FILES = {"README.md"}
INSTANCE_FILE_FIELDS = ("dax_xml", "wfformat_json", "file")
MANIFEST_FIELD = "manifest"
SIZE_FIELD = "sizeBytes"
MANIFEST_SIZE_FIELD = "manifestSizeBytes"
INDEX_SIZE_FIELD = "indexSizeBytes"

def valid_relative(value: object) -> bool:

    if not isinstance(value, str) or not value or "\\" in value:
        return False
    path = Path(value)
    if path.is_absolute() or ".." in path.parts:
        return False
    return True

def valid_size(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0

def tracked_datasets_files(root: Path) -> Optional[List[str]]:

    try:
        result = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z", "--", "datasets"],
            capture_output=True, check=True, timeout=60)
    except (OSError, subprocess.SubprocessError):
        return None
    return [os.fsdecode(part) for part in result.stdout.split(b"\0") if part]

def _entry_main_file(entry: object, subject: str, issues: List[str]) -> Optional[str]:

    if not isinstance(entry, dict):
        issues.append(subject + ": entry is not a JSON object")
        return None
    candidates = [entry.get(field) for field in INSTANCE_FILE_FIELDS
                  if entry.get(field) is not None]
    if len(candidates) != 1:
        issues.append(subject + ": entry does not name exactly one instance file")
        return None
    value = candidates[0]
    if not valid_relative(value):
        issues.append(subject + ": invalid instance file path " + repr(value))
        return None
    return value

def _check_entry_file(base: Path, rel: str, entry: dict,
                      subject: str, issues: List[str], counts: Dict[str, int],
                      size_key: str) -> None:

    path = base / rel
    if path.is_dir():
        issues.append(subject + ": listed path is a directory, not a file")
        return
    recorded = entry.get(size_key)
    if not valid_size(recorded):
        issues.append(subject + ": missing or malformed " + size_key)
        return
    if not path.is_file():
        retained = entry.get("retained_in_standard_checkout", True)
        if retained is not False:
            issues.append(subject + ": file is missing but the entry claims it is"
                          " retained in a standard checkout")
        else:
            counts["absent_not_retained"] += 1
        return
    counts["present_verified"] += 1
    actual = path.stat().st_size
    if actual != recorded:
        issues.append(subject + ": recorded size mismatch (recorded " + str(recorded)
                      + " bytes, actual " + str(actual) + " bytes)")

def check_indexes(root: Path) -> Tuple[List[str], Dict[str, int]]:

    issues: List[str] = []
    counts = {"families": 0, "entries": 0, "present_verified": 0,
              "absent_not_retained": 0, "manifests_verified": 0,
              "tracked_files": 0, "disk_files": 0, "issues": 0}
    datasets = root / "datasets"
    top_path = datasets / INDEX_NAME
    if not top_path.is_file():
        raise ValueError("missing top-level dataset index: datasets/" + INDEX_NAME)
    top = json.loads(top_path.read_text(encoding="utf-8"))
    families = top.get("families")
    if not isinstance(families, list) or not families:
        raise ValueError("the top-level dataset index lists no families")

    covered = set()
    family_indexes = set()
    for family in families:
        if not isinstance(family, dict):
            raise ValueError("a top-level family entry is not a JSON object")
        name = family.get("family")
        index_value = family.get("index")
        if not valid_relative(index_value):
            raise ValueError("family " + repr(name) + " has no valid index path")
        index_path = datasets / index_value
        if not index_path.is_file():
            raise ValueError("family index is missing: datasets/" + index_value)
        recorded_index_size = family.get(INDEX_SIZE_FIELD)
        if not valid_size(recorded_index_size):
            raise ValueError("family " + repr(name) + " " + INDEX_SIZE_FIELD
                             + " is missing or malformed")
        actual_index_size = index_path.stat().st_size
        if actual_index_size != recorded_index_size:
            issues.append("datasets/" + index_value + ": family index size mismatch"
                          " (recorded " + str(recorded_index_size) + " bytes, actual "
                          + str(actual_index_size) + " bytes)")
        covered.add(index_value)
        family_indexes.add(index_value)
        index = json.loads(index_path.read_text(encoding="utf-8"))
        if not isinstance(index, dict):
            raise ValueError("family index datasets/" + index_value + " is not a JSON object")

        instances = index.get("instances")
        if not isinstance(instances, list):
            raise ValueError("family index datasets/" + index_value
                            + " has no instances list")
        declared_n = index.get("n_instances")
        if declared_n != len(instances):
            issues.append("datasets/" + index_value + ": n_instances is "
                          + str(declared_n) + " but the index lists "
                          + str(len(instances)) + " instances")
        declared_top_n = family.get("n_instances")
        if declared_top_n is not None and declared_top_n != len(instances):
            issues.append("datasets/" + INDEX_NAME + ": family " + repr(name)
                          + " declares n_instances " + str(declared_top_n)
                          + " but its index lists " + str(len(instances)))
        auxiliary = index.get("auxiliary_files", [])
        if not isinstance(auxiliary, list):
            raise ValueError("family index datasets/" + index_value
                            + " has a malformed auxiliary_files list")
        declared_aux = index.get("n_auxiliary_files")
        if declared_aux is not None and declared_aux != len(auxiliary):
            issues.append("datasets/" + index_value + ": n_auxiliary_files is "
                          + str(declared_aux) + " but the index lists "
                          + str(len(auxiliary)) + " auxiliary files")

        by_runtime: Dict[str, int] = {}
        base = index_path.parent
        rel_prefix = str(index_path.parent.relative_to(datasets))
        for kind, entry in ([("instance", e) for e in instances]
                            + [("auxiliary", e) for e in auxiliary]):
            subject = ("datasets/" + index_value + " " + kind)
            rel = _entry_main_file(entry, subject, issues)
            counts["entries"] += 1
            if rel is None:
                continue
            covered.add(rel_prefix + "/" + rel)
            _check_entry_file(base, rel, entry, subject, issues, counts, SIZE_FIELD)
            if isinstance(entry, dict) and "runtime" in entry:
                by_runtime[entry["runtime"]] = by_runtime.get(entry["runtime"], 0) + 1
            manifest = entry.get(MANIFEST_FIELD) if isinstance(entry, dict) else None
            if manifest is None:
                continue
            if not valid_relative(manifest):
                issues.append(subject + ": invalid manifest path " + repr(manifest))
                continue
            covered.add(rel_prefix + "/" + manifest)
            manifest_path = base / manifest
            if manifest_path.is_file():
                counts["manifests_verified"] += 1
                recorded_manifest = entry.get(MANIFEST_SIZE_FIELD)
                actual_manifest = manifest_path.stat().st_size
                if not valid_size(recorded_manifest):
                    issues.append(subject + ": manifest is present but "
                                  + MANIFEST_SIZE_FIELD + " is missing or malformed")
                elif actual_manifest != recorded_manifest:
                    issues.append(subject + ": manifest size mismatch (recorded "
                                  + str(recorded_manifest) + " bytes, actual "
                                  + str(actual_manifest) + " bytes)")

        declared_runtime_counts = index.get("counts_by_runtime")
        if isinstance(declared_runtime_counts, dict):
            for runtime, declared in declared_runtime_counts.items():
                if by_runtime.get(runtime, 0) != declared:
                    issues.append("datasets/" + index_value + ": counts_by_runtime"
                                  " declares " + repr(runtime) + " = " + str(declared)
                                  + " but the index lists " + str(by_runtime.get(runtime, 0)))
        counts["families"] += 1

    tracked = tracked_datasets_files(root)
    if tracked is None:
        counts["tracked_files"] = -1
    else:
        counts["tracked_files"] = len(tracked)
        for name in tracked:
            rel = name[len("datasets/"):] if name.startswith("datasets/") else name
            if rel in EXEMPT_FILES or rel == INDEX_NAME or rel in covered:
                continue
            issues.append(name + ": tracked by Git but listed by no dataset index")

    for current, dirnames, filenames in os.walk(datasets):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIR_NAMES)
        for filename in sorted(filenames):
            rel = os.path.relpath(os.path.join(current, filename), datasets)
            rel = rel.replace(os.sep, "/")
            if rel == INDEX_NAME or rel in family_indexes or rel in EXEMPT_FILES:
                continue
            counts["disk_files"] += 1
            if rel not in covered:
                issues.append("datasets/" + rel + ": present on disk but listed by"
                              " no dataset index")
    counts["issues"] = len(issues)
    return issues, counts

def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="""Check the dataset family indexes and the top-level index.

Usage (from any working directory)::

    python3 -B scripts/check-dataset-indexes.py
    python3 -B scripts/check-dataset-indexes.py --root /some/repository

The dataset tree ships one index per family (``datasets/dax/INDEX.json``,
``datasets/wfformat/INDEX.json``, ``datasets/wfinstances/INDEX.json``,
``datasets/wfgen-standard/INDEX.json``) and a top-level ``datasets/INDEX.json``
that lists each family index with its byte size (``indexSizeBytes``). Every index
entry records the relative path and byte size (``sizeBytes``) of the file it
lists; a listed generation manifest carries ``manifestSizeBytes``. The checker
fails when:

- a listed file is present but its size differs from the recorded size,
- a listed file is absent while the entry claims it is retained in a standard
  checkout (a broken checkout),
- a file is present under ``datasets/`` but no index lists it (corpus drift),
- a Git-tracked file under ``datasets/`` is listed by no index and is not an
  explicitly exempt document,
- a family index size recorded in the top-level index does not match the
  family index on disk, or
- a declared entry count disagrees with the actual number of entries.

Absent files flagged ``retained_in_standard_checkout: false`` are legal: the
large WfGen and WfInstances corpora are prepared separately and do not ship
with the repository. Generation manifests listed by ``wfformat`` entries may
be absent for the same reason; when present they must match their recorded
size. The upstream clone metadata directory ``datasets/wfinstances/
.v1.5-git-metadata`` and the top-level ``README.md`` are outside the index
scope: the first is local clone provenance, the second is documentation that
changes freely. This script performs no writes and needs no dependencies.
""",
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path,
                        default=Path(__file__).resolve().parent.parent,
                        help="repository root (default: this script's parent repository)")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        if not root.is_dir():
            raise ValueError("root is not an existing directory: " + str(root))
        issues, counts = check_indexes(root)
    except (OSError, ValueError) as error:
        print("check-dataset-indexes: " + str(error), file=sys.stderr)
        return 2
    tracked = ("skipped (not a Git repository)" if counts["tracked_files"] < 0
               else str(counts["tracked_files"]))
    print("Checked {families} dataset families: {entries} indexed entries,"
          " {present_verified} present files verified,"
          " {absent_not_retained} absent entries not retained,"
          " {manifests_verified} generation manifests verified;"
          " {disk_files} unexempt files found on disk (tracked: {tracked});"
          " {issues} issues.".format(tracked=tracked, **counts))
    for issue in issues:
        print(issue)
    return 1 if issues else 0

if __name__ == "__main__":
    sys.exit(main())
