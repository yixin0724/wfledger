#!/usr/bin/env python3
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def run(command, cwd, log):
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("w", encoding="utf-8") as stream:
        subprocess.run(command, cwd=cwd, check=True, stdout=stream, stderr=subprocess.STDOUT)


def main():
    parser = argparse.ArgumentParser(description="Reproduce FGCS results in a detached clean worktree")
    parser.add_argument("--worktree", required=True)
    parser.add_argument("--repetitions", type=int, default=5)
    args = parser.parse_args()
    if args.repetitions < 1:
        parser.error("repetitions must be positive")
    destination = Path(args.worktree).expanduser().resolve()
    if destination == ROOT or ROOT in destination.parents:
        parser.error("worktree must be outside the source checkout")
    if destination.exists() and any(destination.iterdir()):
        parser.error("worktree must be new or empty")
    report_root = ROOT / "paper/tmp/clean-reproduction"
    report_root.mkdir(parents=True, exist_ok=True)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    subprocess.run(["git", "worktree", "add", "--detach", str(destination), commit], cwd=ROOT, check=True)
    result = {"sourceCommit": commit, "worktree": str(destination), "repetitions": args.repetitions}
    try:
        run(["mvn", "-Pjavadoc", "clean", "verify"], destination, report_root / "maven-verify.log")
        result["mavenVerify"] = "PASS"
        run([sys.executable, "scripts/run-fgcs-matrix.py", "--repetitions", str(args.repetitions)],
            destination, report_root / "matrix.log")
        result["matrix"] = "PASS"
        comparison = subprocess.run([
            sys.executable, "scripts/compare-fgcs-results.py",
            str(ROOT / "paper/tmp/experiments"), str(destination / "paper/tmp/experiments"),
        ], cwd=ROOT, text=True, capture_output=True)
        (report_root / "comparison.log").write_text(comparison.stdout + comparison.stderr, encoding="utf-8")
        if comparison.returncode:
            raise RuntimeError("core result comparison failed")
        result["coreComparison"] = comparison.stdout.strip()
        result["status"] = "PASS"
    except (subprocess.CalledProcessError, RuntimeError) as error:
        result["status"] = "FAIL"
        result["error"] = str(error)
        (report_root / "report.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        raise
    (report_root / "report.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
