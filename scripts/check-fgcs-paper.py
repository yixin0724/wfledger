#!/usr/bin/env python3
import re
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
PAPER = ROOT / "paper/fgcs"


def inspect(paper):
    paper = Path(paper)
    issues = []
    bibliography = paper / "references.bib"
    if not bibliography.is_file():
        return ["missing references.bib"]
    text = bibliography.read_text(encoding="utf-8")
    entries = re.findall(r"^@(\w+)\{([^,]+),", text, re.MULTILINE)
    keys = [key for _, key in entries]
    if len(keys) != len(set(keys)):
        issues.append("duplicate bibliography key")
    for key in keys:
        if not re.fullmatch(r"[A-Za-z0-9_:-]+", key):
            issues.append("invalid bibliography key " + key)
    expected = (
        "volume={128}",
        "pages={16--27}",
        "doi={10.1016/j.future.2021.09.043}",
        "doi={10.1007/978-3-319-61756-5\\_10}",
        "volume={136}",
        "pages={14--28}",
        "year={2020}",
        "pages={104--113}",
        "doi={10.1109/ICPPW.2014.26}",
        "doi={10.1016/j.future.2013.07.005}",
        "doi={10.1016/j.jpdc.2019.09.004}",
        "doi={10.1109/WORKS.2008.4723958}",
    )
    for value in expected:
        if value not in text:
            issues.append("bibliography missing " + value)
    for wrong in ("10.1016/j.future.2022.05.014", "10.1109/ICPADS.2016.0143", "volume={133}", "pages={426--433}"):
        if wrong in text:
            issues.append("stale or incorrect bibliography value " + wrong)
    sections = sorted((paper / "sections").glob("*.tex"))
    body = "\n".join(path.read_text(encoding="utf-8") for path in sections)
    for path in sections:
        if "WorkflowSim-yixin" in path.read_text(encoding="utf-8"):
            issues.append("stale product name in " + path.name)
    if "\\platform uses" in body or "\\platform consumes" in body:
        issues.append("missing space after platform macro")
    if "real scientific workflows" in body or "real DAX experiments" in body:
        issues.append("real-execution wording used for characterization DAX")
    if "2{,}048" in body:
        issues.append("stale Java test count")
    if "Future work will add external differential comparisons" in body:
        issues.append("completed experiments incorrectly listed as future work")
    used = set(re.findall(r"\\cite\{([^}]+)\}", body))
    for group in used:
        for key in group.split(","):
            if key.strip() not in keys:
                issues.append("undefined citation " + key.strip())
    return issues


def main():
    if not PAPER.is_dir():
        print("FGCS_PAPER_SKIPPED local paper workspace is absent")
        return 0
    issues = inspect(PAPER)
    for issue in issues:
        print("FGCS_PAPER_ERROR", issue)
    if issues:
        return 1
    print("FGCS_PAPER_OK bibliography and factual guards")
    return 0


if __name__ == "__main__":
    sys.exit(main())
