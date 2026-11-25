#!/usr/bin/env python3
"""Generate the WfGen standard-workloads corpus deterministically from a declarative spec.

This generator is the only component that imports WfCommons. It runs in a dedicated
virtual environment that has ``wfcommons==1.6`` installed; neither the simulator nor
``scripts/`` import WfCommons (R7-WORKLOAD, 2026-11-25).

Usage (from the repository root)::

    MPLCONFIGDIR=/tmp/mpl <venv>/bin/python -B \\
        experiments/studies/standard-workloads/generate_corpus.py \\
        --spec experiments/studies/standard-workloads/spec.json \\
        --output datasets/wfgen-standard

Determinism rules (documented in README.md next to this file):

* Per-instance seed: the first four bytes (big endian) of
  ``SHA-256("<base_seed>:<recipe>:<requested_tasks>:<index>")``, an integer in
  ``[0, 2**32)``. Python ``random`` and ``numpy.random`` are both seeded with it
  immediately before the instance is built.
* WfCommons draws file IDs from ``uuid4()`` and stamps ``createdAt``,
  ``execution.executedAt``, and ``author.name`` (the login name) from the host. The
  canonicalization replaces each of them; every other byte comes from WfCommons.
* Output is ``json.dumps(sort_keys=True, separators=(",", ":"))`` plus a trailing
  newline, written in binary mode.

A declared exclusion (recipe, size) must actually fail in WfCommons; an exclusion that
builds successfully is reported as an error instead of being skipped silently.
"""

import argparse
import hashlib
import json
import platform
import random
import re
import sys
from importlib import metadata
from pathlib import Path

FIXED_TIMESTAMP = "1970-01-01T00:00:00+00:00"
FIXED_AUTHOR = {"name": "WfLedger standard-workloads generator", "email": "sup***@wfcommons.org"}
UUID_PREFIX = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
RECIPE_CLASSES = {
    "montage": "MontageRecipe",
    "epigenomics": "EpigenomicsRecipe",
    "seismology": "SeismologyRecipe",
    "blast": "BlastRecipe",
    "1000genome": "GenomeRecipe",
    "cycles": "CyclesRecipe",
    "soykb": "SoykbRecipe",
    "srasearch": "SrasearchRecipe",
}
PINNED_DISTRIBUTIONS = ("wfcommons", "numpy", "scipy", "networkx", "pandas")
KINDS = (("inputFiles", "in"), ("outputFiles", "out"))


class CorpusError(Exception):
    """Raised for any spec, generation, or canonicalization failure (fail fast)."""


def derive_seed(base_seed, recipe, requested_tasks, index):
    """Return the documented hash-derived per-instance seed in [0, 2**32)."""
    key = "{}:{}:{}:{}".format(base_seed, recipe, requested_tasks, index).encode("ascii")
    return int.from_bytes(hashlib.sha256(key).digest()[:4], "big")


def canonical_extension(tail):
    """Map the text following the uuid4 prefix to a canonical extension.

    WfCommons appends the recipe's extension key verbatim: usually ``.ext``, but some
    recipes use bare keys (Epigenomics ``maq``, BLAST ``None``). A bare key gains a
    leading dot so the canonical ID keeps a single ``<stem>.<extension>`` shape.
    """
    if tail == "":
        return ""
    if "/" in tail or tail != tail.strip():
        raise CorpusError("unsupported file-ID suffix " + repr(tail))
    return tail if tail.startswith(".") else "." + tail


def canonical_file_ids(tasks, extension_of):
    """Return {old_id: new_id} for every file referenced by ``tasks``.

    Each file is named after the first task, in ascending task-id order, that references
    it (``inputFiles`` scanned before ``outputFiles``), the reference kind, the file's
    0-based position in that task's list, and its extension:
    ``<task_id>.<in|out><ordinal><extension>`` (ordinal zero-padded
    to at least three digits).
    """
    mapping = {}
    for task in sorted(tasks, key=lambda item: item["id"]):
        for field, kind in KINDS:
            for ordinal, file_id in enumerate(task.get(field, [])):
                if file_id not in mapping:
                    mapping[file_id] = "{}.{}{:03d}{}".format(
                        task["id"], kind, ordinal, extension_of(file_id))
    if len(set(mapping.values())) != len(mapping):
        raise CorpusError("canonical file IDs are not unique")
    return mapping


def canonicalize(document):
    """Canonicalize one WfCommons document in place and return it."""
    workflow = document["workflow"]
    specification = workflow["specification"]
    tasks = specification["tasks"]
    files = specification.get("files", [])
    declared = [entry["id"] for entry in files]
    if len(set(declared)) != len(declared):
        raise CorpusError("duplicate file IDs in specification.files")
    tails = {}
    for file_id in declared:
        match = UUID_PREFIX.match(file_id)
        if match is None:
            raise CorpusError("file ID does not start with a uuid4: " + file_id)
        tails[file_id] = canonical_extension(file_id[match.end():])
    referenced = {file_id for task in tasks for field, _ in KINDS
                  for file_id in task.get(field, [])}
    if referenced != set(declared):
        raise CorpusError("file references and specification.files disagree: {} unreferenced,"
                          " {} undeclared".format(len(set(declared) - referenced),
                                                  len(referenced - set(declared))))
    mapping = canonical_file_ids(tasks, lambda file_id: tails[file_id])
    if set(mapping) != set(declared):
        raise CorpusError("not every declared file was renamed")
    for task in tasks:
        for field, _ in KINDS:
            if field in task:
                task[field] = [mapping[file_id] for file_id in task[field]]
    for entry in files:
        entry["id"] = mapping[entry["id"]]
    document["createdAt"] = FIXED_TIMESTAMP
    document["author"] = dict(FIXED_AUTHOR)
    execution = workflow.get("execution")
    if execution is None:
        raise CorpusError("the document has no execution section")
    execution["executedAt"] = FIXED_TIMESTAMP
    for task in execution["tasks"]:
        if "executedAt" in task:
            task["executedAt"] = FIXED_TIMESTAMP
    check_consistency(document)
    return document


def check_consistency(document):
    """Verify the structural invariants the canonicalization must preserve."""
    specification = document["workflow"]["specification"]
    tasks = specification["tasks"]
    ids = [task["id"] for task in tasks]
    known = set(ids)
    if len(known) != len(ids):
        raise CorpusError("duplicate task IDs")
    by_id = {task["id"]: task for task in tasks}
    for task in tasks:
        for child in task["children"]:
            if child not in known or task["id"] not in by_id[child]["parents"]:
                raise CorpusError("asymmetric edge {} -> {}".format(task["id"], child))
        for parent in task["parents"]:
            if parent not in known or task["id"] not in by_id[parent]["children"]:
                raise CorpusError("asymmetric edge {} -> {}".format(parent, task["id"]))
    files = {entry["id"] for entry in specification.get("files", [])}
    for task in tasks:
        for field, _ in KINDS:
            for file_id in task.get(field, []):
                if file_id not in files:
                    raise CorpusError("unresolved file reference " + file_id)
    execution_ids = {task["id"] for task in document["workflow"]["execution"]["tasks"]}
    if execution_ids != known:
        raise CorpusError("execution tasks and specification tasks disagree")


def serialize(document):
    """Return the canonical byte serialization."""
    text = json.dumps(document, sort_keys=True, separators=(",", ":"), ensure_ascii=True,
                      allow_nan=False)
    return (text + "\n").encode("ascii")


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def load_spec(path):
    raw = Path(path).read_bytes()
    spec = json.loads(raw.decode("utf-8"))
    for key in ("corpus", "base_seed", "instances_per_size", "recipes"):
        if key not in spec:
            raise CorpusError("spec is missing " + repr(key))
    if not isinstance(spec["base_seed"], int) or isinstance(spec["base_seed"], bool):
        raise CorpusError("base_seed must be an integer")
    if not isinstance(spec["instances_per_size"], int) or spec["instances_per_size"] < 1:
        raise CorpusError("instances_per_size must be a positive integer")
    for recipe in spec["recipes"]:
        if recipe.get("recipe") not in RECIPE_CLASSES:
            raise CorpusError("unknown recipe " + repr(recipe.get("recipe")))
        sizes = recipe.get("sizes", [])
        excluded = [entry["size"] for entry in recipe.get("excluded_sizes", [])]
        if set(sizes) & set(excluded):
            raise CorpusError("size both requested and excluded for " + recipe["recipe"])
    return spec, raw


def plan(spec):
    """Yield (recipe, requested_tasks, index, seed, relative_path) in a fixed order."""
    for recipe in spec["recipes"]:
        name = recipe["recipe"]
        for size in recipe["sizes"]:
            for index in range(spec["instances_per_size"]):
                rel = "{0}/n{1}/{0}-{1}-{2:03d}.json".format(name, size, index)
                yield name, size, index, derive_seed(spec["base_seed"], name, size, index), rel


def environment():
    versions = {}
    for name in PINNED_DISTRIBUTIONS:
        versions[name] = metadata.version(name)
    return {"python": platform.python_version(), "distributions": versions}


def build_document(recipe_name, requested_tasks, seed):
    import numpy
    from wfcommons import WorkflowGenerator
    import wfcommons.wfchef.recipes as recipes

    recipe_class = getattr(recipes, RECIPE_CLASSES[recipe_name])
    random.seed(seed)
    numpy.random.seed(seed)
    workflow = WorkflowGenerator(recipe_class.from_num_tasks(requested_tasks)).build_workflow()
    workflow.generate_json()
    return json.loads(json.dumps(workflow.workflow_json))


def confirm_exclusions(spec):
    """Each declared exclusion must fail in WfCommons; return the observed messages."""
    observed = []
    for recipe in spec["recipes"]:
        for entry in recipe.get("excluded_sizes", []):
            seed = derive_seed(spec["base_seed"], recipe["recipe"], entry["size"], 0)
            try:
                build_document(recipe["recipe"], entry["size"], seed)
            except ValueError as error:
                observed.append({"recipe": recipe["recipe"], "size": entry["size"],
                                 "reason": entry.get("reason", ""), "observed_error": str(error)})
                continue
            raise CorpusError("declared exclusion {} n{} builds successfully; remove the"
                              " exclusion".format(recipe["recipe"], entry["size"]))
    return observed


def generate(spec_path, output, only_sizes=None):
    spec, raw = load_spec(spec_path)
    output = Path(output)
    retained = set(spec.get("retained_sizes", []))
    instances = []
    for recipe_name, size, index, seed, rel in plan(spec):
        if only_sizes is not None and size not in only_sizes:
            continue
        document = canonicalize(build_document(recipe_name, size, seed))
        data = serialize(document)
        target = output / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        with open(target, "wb") as handle:
            handle.write(data)
        instances.append({
            "recipe": recipe_name,
            "application": document["name"].split("-synthetic-instance")[0],
            "requested_tasks": size,
            "num_tasks": len(document["workflow"]["specification"]["tasks"]),
            "instance_index": index,
            "seed": seed,
            "wfformat_json": rel,
            "sha256": sha256_bytes(data),
            "retained_in_standard_checkout": size in retained,
        })
    manifest = {
        "corpus": spec["corpus"],
        "spec_sha256": sha256_bytes(raw),
        "base_seed": spec["base_seed"],
        "seed_rule": "int.from_bytes(sha256('<base_seed>:<recipe>:<requested_tasks>:<index>')[:4], 'big')",
        "canonicalization": {
            "file_id_rule": "<first referencing task id in ascending order>.<in|out><ordinal zero-padded to at least 3 digits><extension>",
            "fixed_timestamp": FIXED_TIMESTAMP,
            "fixed_author": FIXED_AUTHOR,
            "serialization": "json sort_keys, compact separators, ASCII, trailing newline",
        },
        "environment": environment(),
        "exclusions": confirm_exclusions(spec),
        "n_instances": len(instances),
        "instances": instances,
    }
    manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("ascii")
    with open(output / "GENERATION.json", "wb") as handle:
        handle.write(manifest_bytes)
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--spec", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--only-sizes", type=int, nargs="*",
                        help="generate only these requested sizes (the manifest then lists"
                             " only them; use for partial regeneration checks)")
    args = parser.parse_args(argv)
    try:
        manifest = generate(args.spec, args.output,
                            set(args.only_sizes) if args.only_sizes else None)
    except (CorpusError, OSError, ValueError, KeyError) as error:
        print("generate_corpus: " + str(error), file=sys.stderr)
        return 1
    print("generated {} instances into {}".format(manifest["n_instances"], args.output))
    return 0


if __name__ == "__main__":
    sys.exit(main())
