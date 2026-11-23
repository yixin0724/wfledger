#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
COMPARE = ROOT / "experiments/studies/fgcs/differential/compare.py"
LOCK = ROOT / "experiments/studies/fgcs/differential/upstream-lock.json"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def relative_path(value, label):
    path = Path(value)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise ValueError(label + " must be a nonempty relative path without parent escape")
    return path


def copy_reproduction_inputs(work, output):
    inputs = Path(work) / "inputs"
    if not inputs.is_dir():
        raise ValueError("reproduction did not retain its DAX input directory")
    target = Path(output) / "inputs"
    target.mkdir(parents=True, exist_ok=True)
    count = 0
    for source in inputs.glob("*.dax"):
        shutil.copyfile(source, target / source.name)
        count += 1
    if not count:
        raise ValueError("reproduction input directory contains no DAX files")
    return count


def main():
    parser = argparse.ArgumentParser(description="Generate pinned current/upstream FGCS differential observations")
    parser.add_argument("--output", default="paper/tmp/fgcs-round2/differential")
    parser.add_argument("--work-dir", default="paper/tmp/fgcs-round2-differential-reproduction")
    args = parser.parse_args()
    try:
        output = ROOT / relative_path(args.output, "output")
        work = ROOT / relative_path(args.work_dir, "work-dir")
        if work.exists() and any(work.iterdir()):
            raise ValueError("work-dir must be new or empty to prevent stale upstream observations")
        output.mkdir(parents=True, exist_ok=True)
        archive = os.environ.get("FGCS_UPSTREAM_ARCHIVE", str(ROOT / "paper/tmp/upstream-commit.zip"))
        if not Path(archive).is_file():
            raise ValueError("pinned upstream archive is missing: " + archive)
        environment = dict(os.environ, FGCS_UPSTREAM_ARCHIVE=archive)
        with (output / "reproduction.log").open("w", encoding="utf-8") as log:
            subprocess.run([sys.executable, "-B", str(COMPARE), "--reproduce", "--work-dir", str(work)],
                           cwd=ROOT, env=environment, check=True, stdout=log, stderr=subprocess.STDOUT)
        observations = work / "upstream-observations.json"
        if not observations.is_file():
            raise ValueError("reproduction did not generate upstream-observations.json")
        shutil.copyfile(observations, output / "upstream-observations.json")
        copy_reproduction_inputs(work, output)
        comparison = subprocess.run([sys.executable, "-B", str(COMPARE),
                                     "--observations", str(output / "upstream-observations.json"),
                                     "--output", str(output / "comparison.json"), "--no-table"],
                                    cwd=ROOT, text=True, capture_output=True)
        (output / "comparison.log").write_text(comparison.stdout + comparison.stderr, encoding="utf-8")
        if comparison.returncode:
            raise ValueError("differential comparison failed")
        with (output / "adjudication.log").open("w", encoding="utf-8") as log:
            subprocess.run([sys.executable, "-B", str(ROOT / "scripts/analyze-fgcs-differential.py"),
                            str(output / "upstream-observations.json"), str(output / "adjudication.json")],
                           cwd=ROOT, check=True, stdout=log, stderr=subprocess.STDOUT)
        lock = json.loads(LOCK.read_text(encoding="utf-8"))
        metadata = {
            "upstreamCommit": lock["commit"],
            "upstreamArchive": Path(archive).name,
            "upstreamArchiveSha256": sha256(archive),
            "observationsSha256": sha256(output / "upstream-observations.json"),
            "comparisonSha256": sha256(output / "comparison.json"),
            "sourceRoot": str(ROOT),
        }
        (output / "observation-provenance.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        print("FGCS_DIFFERENTIAL_OBSERVATIONS_READY", metadata["observationsSha256"])
    except (ValueError, OSError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print("FGCS_DIFFERENTIAL_PREPARATION_FAILED:", error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
