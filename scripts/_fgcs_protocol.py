"""Execution-identity guard shared by the FGCS drivers (run-fgcs-matrix.py, run-fgcs-revision.py).

FGCS-R3 (2026-11-24): the FGCS protocol (experiments/studies/fgcs/protocol.json) declares the execution identity
that every newly generated manifest must carry. Without this guard, retained V2-era evidence under
paper/tmp/fgcs-round2 would satisfy the row-count verifiers and impersonate results of the corrected V3 code.
The declared value mirrors TaskExecutionModel.EXECUTION_SEMANTICS and _study_audit.EXECUTION_SEMANTICS.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = ROOT / "experiments/studies/fgcs/protocol.json"
MANIFEST_NAME = "result.manifest.json"


def declared_execution_semantics(protocol=PROTOCOL):
    """Return the current execution identity that the FGCS protocol requires; fail when it is undeclared."""
    document = json.loads(Path(protocol).read_text(encoding="utf-8"))
    value = document.get("executionSemantics")
    if not isinstance(value, str) or not value:
        raise ValueError("FGCS protocol does not declare executionSemantics: " + str(protocol))
    if value in document.get("historicalExecutionSemantics", []):
        raise ValueError("FGCS protocol declares a historical execution identity as current: " + value)
    return value


def check_manifest_semantics(root, expected, required=True):
    """Check every result manifest under ``root``; return (count, failures).

    A missing or different ``configuration.executionSemantics`` is a failure: historical evidence is never
    accepted as a new FGCS run. ``required`` makes an evidence tree without any manifest a failure as well.
    """
    root = Path(root)
    failures = []
    paths = sorted(root.rglob(MANIFEST_NAME)) if root.is_dir() else []
    for path in paths:
        try:
            configuration = json.loads(path.read_text(encoding="utf-8")).get("configuration") or {}
        except (OSError, json.JSONDecodeError) as error:
            failures.append(str(path) + ": unreadable manifest (" + str(error) + ")")
            continue
        actual = configuration.get("executionSemantics")
        if actual != expected:
            failures.append(str(path) + ": executionSemantics " + repr(actual) + " is not the protocol's "
                            + repr(expected))
    if required and not paths:
        failures.append(str(root) + ": no " + MANIFEST_NAME + " to bind the declared execution identity")
    return len(paths), failures
