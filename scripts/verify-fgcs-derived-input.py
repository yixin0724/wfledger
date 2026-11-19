#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def name(element):
    return element.attrib.get("file", element.attrib.get("name", ""))


def tag(element):
    return element.tag.rsplit("}", 1)[-1]


def elements(root, local_name):
    return [element for element in root.iter() if tag(element) == local_name]


def jobs(root):
    return {job.attrib["id"]: job for job in elements(root, "job")}


def uses(job):
    return [child for child in list(job) if tag(child) == "uses"]


def edges(root):
    return {
        (child.attrib["ref"], parent.attrib["ref"])
        for child in elements(root, "child")
        for parent in list(child)
        if tag(parent) == "parent"
    }


def verify(source, derived, sidecar):
    source = Path(source)
    derived = Path(derived)
    sidecar = Path(sidecar)
    provenance = json.loads(sidecar.read_text(encoding="utf-8"))
    if provenance.get("sourceSha256") != sha256(source):
        raise ValueError("source hash does not match derivation sidecar")
    if provenance.get("derivedSha256") != sha256(derived):
        raise ValueError("derived hash does not match derivation sidecar")
    if provenance.get("rawInputEquivalenceClaimed") is not False:
        raise ValueError("derived input must explicitly disclaim raw equivalence")
    source_root = ET.parse(source).getroot()
    derived_root = ET.parse(derived).getroot()
    source_jobs = jobs(source_root)
    derived_jobs = jobs(derived_root)
    if not source_jobs or not derived_jobs:
        raise ValueError("empty workflow is not a valid derivation")
    if set(source_jobs) != set(derived_jobs):
        raise ValueError("job identity changed")
    if edges(source_root) != edges(derived_root):
        raise ValueError("DAG edges changed")
    if provenance.get("dependencyEdgeCount") != len(edges(source_root)):
        raise ValueError("dependency edge count mismatch")
    output_names = set()
    output_by_job = {}
    for job_id, source_job in source_jobs.items():
        derived_job = derived_jobs[job_id]
        if source_job.attrib.get("runtime") != derived_job.attrib.get("runtime"):
            raise ValueError("runtime changed for " + job_id)
        source_outputs = [(name(use), use.attrib["size"]) for use in uses(source_job)
                          if use.attrib.get("link") == "output"]
        derived_outputs = [(name(use), use.attrib["size"]) for use in uses(derived_job)
                           if use.attrib.get("link") == "output"]
        if [size for _, size in source_outputs] != [size for _, size in derived_outputs]:
            raise ValueError("producer output bytes changed for " + job_id)
        output_by_job[job_id] = {
            original: (derived_name, size)
            for (original, _), (derived_name, size) in zip(source_outputs, derived_outputs)
        }
        for (original, _), (derived_name, _) in zip(source_outputs, derived_outputs):
            if derived_name != original + "__producer_" + job_id:
                raise ValueError("unexpected producer identity for " + job_id)
            if derived_name in output_names:
                raise ValueError("duplicate derived producer " + derived_name)
            output_names.add(derived_name)
    parent_map = {}
    for child in elements(source_root, "child"):
        parent_map[child.attrib["ref"]] = [parent.attrib["ref"] for parent in list(child) if tag(parent) == "parent"]
    external_sizes = {}
    for source_job in source_jobs.values():
        for use in uses(source_job):
            if use.attrib.get("link") == "input":
                external_sizes.setdefault(name(use), use.attrib["size"])
    expansions = 0
    additional = 0
    for job_id, source_job in source_jobs.items():
        derived_inputs = [use for use in uses(derived_jobs[job_id]) if use.attrib.get("link") == "input"]
        source_inputs = [use for use in uses(source_job) if use.attrib.get("link") == "input"]
        remaining = list(derived_inputs)
        for source_use in source_inputs:
            original = name(source_use)
            candidates = [(parent, output_by_job[parent][original][0], output_by_job[parent][original][1])
                          for parent in parent_map.get(job_id, []) if original in output_by_job[parent]]
            if candidates:
                expected = {candidate for _, candidate, _ in candidates}
                matched = [use for use in remaining if name(use) in expected]
                if len(matched) != len(candidates):
                    raise ValueError("parent input expansion mismatch for " + job_id + "/" + original)
                for use in matched:
                    if use.attrib["size"] not in {size for _, _, size in candidates}:
                        raise ValueError("parent input bytes mismatch for " + job_id + "/" + original)
                    remaining.remove(use)
                if len(candidates) > 1:
                    expansions += 1
                    additional += len(candidates) - 1
            else:
                matched = [use for use in remaining if name(use) == original]
                if len(matched) != 1 or matched[0].attrib["size"] != external_sizes[original]:
                    raise ValueError("external input declaration mismatch for " + job_id + "/" + original)
                remaining.remove(matched[0])
        if remaining:
            raise ValueError("unexplained derived inputs for " + job_id)
    if provenance.get("multiProducerInputExpansions", 0) != expansions:
        raise ValueError("multi-producer expansion count mismatch")
    if provenance.get("additionalInputDeclarations", 0) != additional:
        raise ValueError("additional input declaration count mismatch")
    return {
        "status": "PASS",
        "sourceSha256": sha256(source),
        "derivedSha256": sha256(derived),
        "jobs": len(source_jobs),
        "edges": len(edges(source_root)),
        "uniqueProducers": len(output_names),
        "multiProducerInputExpansions": expansions,
        "additionalInputDeclarations": additional,
    }


def main():
    parser = argparse.ArgumentParser(description="Verify FGCS unique-producer input derivation")
    parser.add_argument("source")
    parser.add_argument("derived")
    parser.add_argument("sidecar")
    args = parser.parse_args()
    result = verify(args.source, args.derived, args.sidecar)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, ET.ParseError, json.JSONDecodeError) as error:
        print("FGCS_DERIVED_INPUT_FAILED:", error)
        raise SystemExit(1)
