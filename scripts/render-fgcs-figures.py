#!/usr/bin/env python3
"""Generate self-contained PGF vector figures from retained FGCS observations."""
import argparse
import json
import math
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
FIGURES = ROOT / "paper/fgcs/figures"


def point(x, y):
    return "\\pgfpoint{" + str(x) + "mm}{" + str(y) + "mm}"


def text(x, y, value, size="scriptsize"):
    command = size if size.startswith("\\") else "\\" + size
    return "\\pgftext[at={" + point(x, y) + "}]{" + command + " " + value + "}\n"


def line(x1, y1, x2, y2, color="black"):
    return ("\\color{" + color + "}\\pgfpathmoveto{" + point(x1, y1) + "}"
            "\\pgfpathlineto{" + point(x2, y2) + "}\\pgfusepath{stroke}\\color{black}\n")


def box(x, y, width, height, value):
    label = "\\shortstack{" + value.replace("\\n", "\\\\") + "}"
    return (text(x, y, label) + "\\pgfpathrectangle{" + point(x - width / 2, y - height / 2) + "}"
            "{" + point(width, height) + "}\\pgfusepath{stroke}\n")


def arrow(x1, y1, x2, y2):
    return ("\\pgfsetarrowsend{latex}\\pgfpathmoveto{" + point(x1, y1) + "}"
            "\\pgfpathlineto{" + point(x2, y2) + "}\\pgfusepath{stroke}\n")


def marker(x, y, color):
    return ("\\color{" + color + "}\\pgfpathcircle{" + point(x, y) + "}{0.7mm}"
            "\\pgfusepath{fill}\\color{black}\n")


def policy_marker(x, y, policy, color):
    if policy == "default":
        return marker(x, y, color)
    if policy == "MIN_POWER":
        return ("\\color{" + color + "}\\pgfpathrectangle{" + point(x - 0.8, y - 0.8) + "}"
                "{" + point(1.6, 1.6) + "}\\pgfusepath{fill}\\color{black}\n")
    if policy == "EDP_HEURISTIC":
        return ("\\color{" + color + "}\\pgfpathmoveto{" + point(x, y + 0.9) + "}"
                "\\pgfpathlineto{" + point(x - 0.9, y - 0.8) + "}\\pgfpathlineto{" + point(x + 0.9, y - 0.8) + "}"
                "\\pgfpathclose\\pgfusepath{fill}\\color{black}\n")
    return ("\\color{" + color + "}" + line(x - 0.8, y - 0.8, x + 0.8, y + 0.8, color)
            + line(x - 0.8, y + 0.8, x + 0.8, y - 0.8, color) + "\\color{black}\n")


def execution_figure():
    stages = [(0, "Dependency\\nready", "Parent and\\nattempt identity"),
              (37, "Planning or\\ntarget assignment", "Decision, VM,\\nand order"),
              (77, "Input visibility\\nand preparation", "Sources, bytes,\\nand visibility"),
              (114, "CPU execution\\nand completion", "Start, finish,\\nand tier"),
              (151, "Output commit\\nand terminal drain", "Obligations and\\nsimulation end")]
    body = "\\begin{pgfpicture}\n"
    for x, stage, evidence in stages:
        body += box(x, 12, 33, 10, stage) + box(x, 0, 33, 10, evidence)
        body += arrow(x, 7, x, 5)
    for (x, _, _), (next_x, _, _) in zip(stages, stages[1:]):
        body += arrow(x + 16.5, 12, next_x - 16.5, 12)
    body += text(75.5, -10, "Data preparation may overlap VM busy intervals; a failed attempt returns to target assignment.")
    return body + "\\end{pgfpicture}"


def evidence_figure():
    body = "\\begin{pgfpicture}\n"
    stages = [(32, "Inputs, configuration,\\nplatform, source identity"),
              (18, "Declared execution\\nand decision records"),
              (4, "Manifest, metrics, events,\\nfile/storage lifecycles")]
    for y, label in stages:
        body += box(24, y, 48, 10, label)
    body += arrow(24, 27, 24, 23) + arrow(24, 13, 24, 9)
    body += box(79, 4, 45, 10, "Independent validators\\nrecompute and causality")
    body += arrow(48, 4, 56.5, 4)
    body += box(79, -10, 45, 10, "Identity, divergence,\\ninvalid, or refusal")
    body += arrow(79, -1, 79, -5)
    body += box(24, -10, 48, 10, "Re-simulation and\\nexact core identity")
    body += arrow(24, -1, 24, -5) + arrow(48, -10, 56.5, -10)
    body += text(51.5, -19, "\\shortstack{Independent of the production execution path;\\\\auditor scope remains explicit.}")
    return body + "\\end{pgfpicture}"


def sensitivity_rows(rows):
    selected = {}
    for row in rows:
        label = row["label"]
        if "-r2-b15-" not in label:
            continue
        workflow = Path(label.split("-v", 1)[0]).stem.replace("_50", "")
        vms = int(label.split("-v", 1)[1].split("-", 1)[0])
        selected.setdefault((workflow, vms), {})[label.rsplit("-", 1)[1]] = row["makespanSeconds"]
    return selected


def sensitivity_figure(rows):
    selected = sensitivity_rows(rows)
    colors = {"Montage": "blue", "CyberShake": "red", "Inspiral": "teal"}
    body = "\\begin{pgfpicture}\n" + line(0, 0, 95, 0) + line(0, 0, 0, 56)
    for vms, x in ((1, 5), (2, 30), (4, 65), (8, 90)):
        body += text(x, -3, str(vms))
    body += text(47, -7, "VM count")
    body += text(47, -12, "$\\operatorname{sgn}(\\Delta)\\log(1+|\\Delta|)$; $\\Delta$=relative Min-Min--HEFT difference")
    body += text(47, -16, "Ties: abs(deltaT) <= 1e-9 s or relative <= 1e-12")
    for percent, transformed in ((-100, -math.log(2)), (0, 0), (100, math.log(2)), (1000, math.log(11))):
        y = 24 + 11 * transformed
        body += line(-0.7, y, 0.7, y) + text(-10, y, str(percent) + "\\%")
    body += line(0, 24, 95, 24, "black!40")
    for workflow in ("Montage", "CyberShake", "Inspiral"):
        points = []
        for vms, x in ((1, 5), (2, 30), (4, 65), (8, 90)):
            pair = selected[(workflow, vms)]
            delta = (pair["READY_BATCH_MINMIN"] - pair["STATIC"]) / pair["STATIC"]
            sign = 1 if delta > 0 else -1 if delta < 0 else 0
            y = 24 + 11 * sign * math.log1p(abs(delta))
            if not 0 <= y <= 56:
                raise ValueError("sensitivity point outside declared axis")
            points.append((x, y))
        for (x1, y1), (x2, y2) in zip(points, points[1:]):
            body += line(x1, y1, x2, y2, colors[workflow])
        for x, y in points:
            body += marker(x, y, colors[workflow])
    for workflow, color, x in (("Montage", "blue", 3), ("CyberShake", "red", 32), ("Inspiral", "teal", 66)):
        body += line(x, 59, x + 5, 59, color) + text(x + 12, 59, workflow)
    return body + "\\end{pgfpicture}"


def energy_figure(rows):
    groups = {}
    for row in rows:
        if "-nonmonotonic-" not in row["label"]:
            groups.setdefault(Path(row["label"]).stem, {})[row["label"].rsplit("-", 1)[1]] = row
    colors = {"Montage_50": "blue", "CyberShake_50": "red", "Sipht_60": "orange",
              "Inspiral_50": "teal", "Epigenomics_46": "purple"}
    policies = ("default", "MIN_POWER", "EDP_HEURISTIC", "dvfs")
    body = "\\begin{pgfpicture}\n" + line(0, 0, 95, 0) + line(0, 0, 0, 42)
    for value in (0.8, 1.0, 1.5, 2.0):
        x = (value - 0.8) * 65
        body += line(x, -0.7, x, 0.7) + text(x, -3, str(value))
    for value in (0.25, 0.5, 0.75, 1.0):
        y = value * 34
        body += line(-0.7, y, 0.7, y) + text(-7, y, str(value))
    for workflow in sorted(groups):
        baseline = groups[workflow]["default"]
        for policy in policies:
            row = groups[workflow].get(policy)
            if row:
                x = (row["makespanSeconds"] / baseline["makespanSeconds"] - 0.8) * 65
                y = row["energyJoules"] / baseline["energyJoules"] * 34
                body += policy_marker(x, y, policy, colors[workflow])
    body += text(47, -7, "Makespan / default HEFT")
    body += text(47, -12, "Energy / default HEFT")
    names = {"Montage_50": "Montage", "CyberShake_50": "CyberShake", "Sipht_60": "SIPHT",
             "Inspiral_50": "Inspiral", "Epigenomics_46": "Epigenomics"}
    for index, (workflow, color) in enumerate(colors.items()):
        x, y = (index % 3) * 32 + 7, 52 - (index // 3) * 5
        body += marker(x, y, color) + text(x + 12, y, names[workflow], "tiny")
    for policy, label, x in (("default", "default", 3), ("MIN_POWER", "MIN\\_POWER", 27),
                             ("EDP_HEURISTIC", "EDP", 52), ("dvfs", "DVFS", 75)):
        body += policy_marker(x, 36, policy, "black") + text(x + 9, 36, label, "tiny")
    return body + "\\end{pgfpicture}"


def compile_vector(name, body):
    if "\\begin{pgfpicture}" not in body or body.count("\\pgfusepath") < 2:
        raise ValueError("refusing to generate a blank vector figure: " + name)
    work = ROOT / "paper/tmp/figure-build"
    work.mkdir(parents=True, exist_ok=True)
    FIGURES.mkdir(parents=True, exist_ok=True)
    source = work / (name + ".tex")
    source.write_text("\\documentclass[border=3pt]{standalone}\n\\usepackage{pgf}\n\\usepackage{amsmath}\n\\begin{document}\n"
                      + body + "\n\\end{document}\n", encoding="utf-8")
    result = subprocess.run(["tectonic", "--outdir", str(work), str(source)], cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    output = FIGURES / (name + ".pdf")
    output.write_bytes((work / (name + ".pdf")).read_bytes())
    info = subprocess.check_output(["pdfinfo", str(output)], text=True)
    dimensions = [float(value) for value in info.split("Page size:")[1].split("pts")[0].split("x")]
    extracted = subprocess.check_output(["pdftotext", str(output), "-"], text=True).strip()
    if output.stat().st_size < 2000 or min(dimensions) < 20 or len(extracted) < 20:
        raise ValueError("generated figure is blank or too small: " + name)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", default="paper/tmp/fgcs-round2")
    args = parser.parse_args()
    root = ROOT / args.input
    sensitivity = json.loads((root / "sensitivity/results.json").read_text(encoding="utf-8"))
    energy = json.loads((root / "energy/results.json").read_text(encoding="utf-8"))
    for name, body in (("execution-semantics", execution_figure()), ("evidence-chain", evidence_figure()),
                       ("sensitivity-effects", sensitivity_figure(sensitivity)), ("energy-tradeoffs", energy_figure(energy))):
        print("FGCS_FIGURE", compile_vector(name, body))


if __name__ == "__main__":
    main()
