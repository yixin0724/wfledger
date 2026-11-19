#!/usr/bin/env python3
import argparse
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

EXECUTION = r"""\begin{tikzpicture}[
  font=\scriptsize,
  stage/.style={draw, rounded corners=1pt, align=center, minimum height=6mm, text width=20mm, inner sep=2pt},
  evidence/.style={draw, dashed, align=center, text width=21mm, inner sep=2pt},
  arr/.style={-{Latex[length=1.5mm]}, thick},
  ev/.style={-{Latex[length=1.2mm]}, dashed}
]
\node[stage] (ready) {Dependency\\ready};
\node[stage, right=3mm of ready] (assign) {Planning or\\target assignment};
\node[stage, right=3mm of assign] (input) {Input visibility\\and preparation};
\node[stage, right=3mm of input] (cpu) {CPU execution\\and completion};
\node[stage, right=3mm of cpu] (commit) {Output commit\\and terminal drain};
\draw[arr] (ready) -- (assign);
\draw[arr] (assign) -- (input);
\draw[arr] (input) -- (cpu);
\draw[arr] (cpu) -- (commit);
\node[evidence, below=5mm of ready] (e1) {Parent/attempt\\identity};
\node[evidence, below=5mm of assign] (e2) {Decision, VM,\\and order};
\node[evidence, below=5mm of input] (e3) {Sources, bytes,\\and visibility};
\node[evidence, below=5mm of cpu] (e4) {Start, finish,\\and tier};
\node[evidence, below=5mm of commit] (e5) {Obligations and\\simulation end};
\draw[ev] (ready) -- (e1);
\draw[ev] (assign) -- (e2);
\draw[ev] (input) -- (e3);
\draw[ev] (cpu) -- (e4);
\draw[ev] (commit) -- (e5);
\end{tikzpicture}"""

EVIDENCE = r"""\begin{tikzpicture}[
  font=\scriptsize,
  box/.style={draw, rounded corners=1pt, align=center, minimum height=7mm, text width=29mm, inner sep=3pt},
  check/.style={draw, dashed, align=center, text width=29mm, inner sep=3pt},
  arr/.style={-{Latex[length=1.5mm]}, thick}
]
\node[box] (input) {Inputs, configuration,\\platform, source identity};
\node[box, below=4mm of input] (run) {Declared execution\\and decision records};
\node[box, below=4mm of run] (artifacts) {Manifest, metrics, events,\\file/storage lifecycles};
\node[check, right=8mm of artifacts] (audit) {Independent validators:\\recompute and causality};
\node[check, below=4mm of artifacts] (replay) {Semantic replay:\\declared core identity};
\node[check, right=8mm of replay] (verdict) {Identity, divergence,\\invalid, refusal};
\draw[arr] (input) -- (run);
\draw[arr] (run) -- (artifacts);
\draw[arr] (artifacts) -- (audit);
\draw[arr] (artifacts) -- (replay);
\draw[arr] (audit) -- (verdict);
\draw[arr] (replay) -- (verdict);
\node[align=left, text width=76mm, below=4mm of verdict, font=\scriptsize] (scope) {Independent of the production execution path; shared model assumptions and auditor logic remain explicit.};
\end{tikzpicture}"""


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text + "\n", encoding="utf-8")


def compile_vector(name, body):
    work = ROOT / "paper/tmp/figure-build"
    work.mkdir(parents=True, exist_ok=True)
    source = work / (name + ".tex")
    document = "\\documentclass[border=3pt]{standalone}\n\\usepackage{pgf}\n\\begin{document}\n" + body + "\n\\end{document}\n"
    source.write_text(document, encoding="utf-8")
    result = subprocess.run(["tectonic", "--outdir", str(work), str(source)], cwd=ROOT,
                            text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    (ROOT / "paper/fgcs/figures" / (name + ".pdf")).write_bytes((work / (name + ".pdf")).read_bytes())
    return (work / (name + ".pdf"))


def image(name):
    return "\\begin{pgfpicture}\\end{pgfpicture}"


def sensitivity(rows):
    selected = {}
    for row in rows:
        label = row["label"]
        if "-r2-b15-" not in label:
            continue
        workflow = Path(label.split("-v", 1)[0]).stem.replace("_50", "")
        vms = int(label.split("-v", 1)[1].split("-", 1)[0])
        scheduler = label.rsplit("-", 1)[1]
        selected.setdefault((workflow, vms), {})[scheduler] = row["makespanSeconds"]
    colors = {"Montage": "blue", "CyberShake": "red", "Inspiral": "teal"}
    parts = [r"""\begin{tikzpicture}
\begin{axis}[
  width=\columnwidth, height=54mm,
  xlabel={VM count}, ylabel={(Min-Min -- HEFT) / HEFT (\%)},
  xtick={1,2,4,8}, xmin=0.5, xmax=8.5,
  legend style={font=\scriptsize, at={(0.5,1.03)}, anchor=south, legend columns=3},
  grid=major, grid style={dotted},
  tick label style={font=\scriptsize}, label style={font=\scriptsize}
]"""]
    for workflow in ("Montage", "CyberShake", "Inspiral"):
        parts.append("\\addplot[color=%s,mark=*,thick] coordinates {" % colors[workflow])
        for vms in (1, 2, 4, 8):
            pair = selected[(workflow, vms)]
            delta = 100.0 * (pair["READY_BATCH_MINMIN"] - pair["STATIC"]) / pair["STATIC"]
            parts.append("  (%d,%.6f)" % (vms, delta))
        parts.append("};\n\\addlegendentry{%s}" % workflow)
    parts.append("\\end{axis}\n\\end{tikzpicture}")
    return "\n".join(parts)


def energy(rows):
    groups = {}
    for row in rows:
        if "-nonmonotonic-" in row["label"]:
            continue
        workflow = Path(row["label"]).stem
        policy = row["label"].rsplit("-", 1)[1]
        groups.setdefault(workflow, {})[policy] = row
    parts = [r"""\begin{tikzpicture}
\begin{axis}[
  width=\columnwidth, height=55mm,
  xlabel={Makespan relative to default HEFT},
  ylabel={Energy relative to default HEFT},
  xmin=0.8, xmax=2.2, ymin=0.05, ymax=1.15,
  legend style={font=\scriptsize, at={(0.5,1.03)}, anchor=south, legend columns=4},
  grid=major, grid style={dotted},
  tick label style={font=\scriptsize}, label style={font=\scriptsize}
]"""]
    marks = {"default": "*", "MIN_POWER": "square*", "EDP_HEURISTIC": "triangle*", "dvfs": "diamond*"}
    for policy, mark in marks.items():
        parts.append("\\addplot[only marks,mark=%s,mark size=1.5pt] coordinates {" % mark)
        for workflow in sorted(groups):
            baseline = groups[workflow]["default"]
            row = groups[workflow].get(policy)
            if row:
                parts.append("  (%.6f,%.6f)" % (row["makespanSeconds"] / baseline["makespanSeconds"],
                                               row["energyJoules"] / baseline["energyJoules"]))
        parts.append("};\n\\addlegendentry{%s}" % policy)
    parts.append("\\end{axis}\n\\end{tikzpicture}")
    return "\n".join(parts)


def box(x, y, width, height, text, dashed=False):
    dash = ",dash pattern=on 2pt off 1pt" if dashed else ""
    label = text.replace("\\n", "\\\\")
    return ("\\pgftext[at={\\pgfpoint{" + str(x) + "mm}{" + str(y) + "mm}}]{" + label + "}\n"
            + "\\pgfpathrectangle{\\pgfpoint{" + str(x - width / 2) + "mm}{" + str(y - height / 2) + "mm}}"
            + "{\\pgfpoint{" + str(width) + "mm}{" + str(height) + "mm}}\\pgfsetstrokeoptions{stroke,0.5pt" + dash + "}\\pgfusepath{stroke}\n")


def arrow(x1, y1, x2, y2):
    return ("\\pgfsetarrowsend{latex}\\pgfpathmoveto{\\pgfpoint{" + str(x1) + "mm}{" + str(y1) + "mm}}"
            "\\pgfpathlineto{\\pgfpoint{" + str(x2) + "mm}{" + str(y2) + "mm}}\\pgfusepath{stroke}\n")


def line(x1, y1, x2, y2, color="black"):
    return ("\\color{" + color + "}\\pgfpathmoveto{\\pgfpoint{" + str(x1) + "mm}{" + str(y1) + "mm}}"
            "\\pgfpathlineto{\\pgfpoint{" + str(x2) + "mm}{" + str(y2) + "mm}}\\pgfusepath{stroke}\\color{black}\n")


def execution_figure():
    body = "\\begin{pgfpicture}\n"
    stages = [(0, "Dependency\\nready"), (36, "Planning or\\ntarget assignment"), (76, "Input visibility\\nand preparation"),
              (113, "CPU execution\\nand completion"), (149, "Output commit\\nand terminal drain")]
    for x, label in stages:
        body += box(x, 12, 33, 9, label) + box(x, 0, 33, 8, "Evidence\\nobservation", True)
        body += arrow(x, 7.5, x, 4)
    for (x, _), (next_x, _) in zip(stages, stages[1:]):
        body += arrow(x + 16.5, 12, next_x - 16.5, 12)
    body += "\\end{pgfpicture}"
    return image("execution-semantics")


def evidence_figure():
    body = "\\begin{pgfpicture}\n"
    for y, label in [(32, "Inputs, configuration,\\nplatform, source identity"),
                     (19, "Declared execution\\nand decision records"),
                     (6, "Manifest, metrics, events,\\nfile/storage lifecycles")]:
        body += box(24, y, 47, 9, label)
    body += arrow(24, 27.5, 24, 23.5) + arrow(24, 14.5, 24, 10.5)
    body += box(75, 6, 39, 9, "Independent validators\\nand semantic replay", True)
    body += arrow(47.5, 6, 55.5, 6)
    body += box(75, -7, 39, 8, "Identity, divergence,\\ninvalid, refusal", True)
    body += arrow(75, 1.5, 75, -3)
    body += box(24, -7, 47, 8, "Independent of the\\nproduction execution path", True)
    body += arrow(24, 1.5, 24, -3)
    body += "\\end{pgfpicture}"
    return image("evidence-chain")


def sensitivity_figure(rows):
    selected = {}
    for row in rows:
        label = row["label"]
        if "-r2-b15-" not in label:
            continue
        workflow = Path(label.split("-v", 1)[0]).stem.replace("_50", "")
        vms = int(label.split("-v", 1)[1].split("-", 1)[0])
        selected.setdefault((workflow, vms), {})[label.rsplit("-", 1)[1]] = row["makespanSeconds"]
    colors = {"Montage": "blue", "CyberShake": "red", "Inspiral": "teal"}
    body = "\\begin{pgfpicture}\n"
    body += line(0, 0, 95, 0) + line(0, 0, 0, 42)
    for vms, x in ((1, 5), (2, 30), (4, 65), (8, 90)):
        body += "\\pgftext[at={\\pgfpoint{" + str(x) + "mm}{-3mm}}]{\\scriptsize " + str(vms) + "}\n"
    body += "\\pgftext[at={\\pgfpoint{47mm}{-7mm}}]{\\scriptsize VM count}\n"
    body += "\\pgftext[at={\\pgfpoint{42mm}{-12mm}}]{\\scriptsize (Min-Min -- HEFT) / HEFT (\\%)}\n"
    for workflow in ("Montage", "CyberShake", "Inspiral"):
        points = []
        for vms, x in ((1, 5), (2, 30), (4, 65), (8, 90)):
            pair = selected[(workflow, vms)]
            delta = 100.0 * (pair["READY_BATCH_MINMIN"] - pair["STATIC"]) / pair["STATIC"]
            y = 4 + 0.008 * delta
            points.append((x, y))
        for (x1, y1), (x2, y2) in zip(points, points[1:]):
            body += line(x1, y1, x2, y2, colors[workflow])
        for x, y in points:
            body += "\\color{" + colors[workflow] + "}\\pgfpathcircle{\\pgfpoint{" + str(x) + "mm}{" + str(y) + "mm}}{0.7mm}\\pgfusepath{fill}\\color{black}\n"
    body += "\\end{pgfpicture}"
    return image("sensitivity-effects")


def energy_figure(rows):
    groups = {}
    for row in rows:
        if "-nonmonotonic-" in row["label"]:
            continue
        groups.setdefault(Path(row["label"]).stem, {})[row["label"].rsplit("-", 1)[1]] = row
    body = "\\begin{pgfpicture}\n"
    body += line(0, 0, 95, 0) + line(0, 0, 0, 42)
    colors = {"default": "black", "MIN_POWER": "blue", "EDP_HEURISTIC": "red", "dvfs": "teal"}
    for workflow in sorted(groups):
        baseline = groups[workflow]["default"]
        for policy, color in colors.items():
            row = groups[workflow].get(policy)
            if row:
                x = (row["makespanSeconds"] / baseline["makespanSeconds"] - 0.8) * 65
                y = row["energyJoules"] / baseline["energyJoules"] * 34
                body += "\\color{" + color + "}\\pgfpathcircle{\\pgfpoint{" + str(x) + "mm}{" + str(y) + "mm}}{0.7mm}\\pgfusepath{fill}\\color{black}\n"
    body += "\\pgftext[at={\\pgfpoint{47mm}{-4mm}}]{\\scriptsize Makespan / default HEFT (x-axis starts at 0.8)}\n"
    body += "\\pgftext[at={\\pgfpoint{47mm}{-9mm}}]{\\scriptsize Energy / default HEFT}\n"
    body += "\\end{pgfpicture}"
    return image("energy-tradeoffs")


def main():
    parser = argparse.ArgumentParser(description="Generate editable vector figures from retained FGCS results")
    parser.add_argument("--input", default="paper/tmp/experiments")
    parser.add_argument("--output", default="paper/fgcs/figures")
    args = parser.parse_args()
    root = ROOT / args.input
    output = ROOT / args.output
    compile_vector("execution-semantics", execution_figure())
    compile_vector("evidence-chain", evidence_figure())
    compile_vector("sensitivity-effects", sensitivity_figure(json.loads((root / "sensitivity/results.json").read_text())))
    compile_vector("energy-tradeoffs", energy_figure(json.loads((root / "energy/results.json").read_text())))
    print("FGCS_FIGURES_WRITTEN", output)


if __name__ == "__main__":
    main()
