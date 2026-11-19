#!/usr/bin/env python3
import argparse
import json
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


def main():
    parser = argparse.ArgumentParser(description="Generate editable vector figures from retained FGCS results")
    parser.add_argument("--input", default="paper/tmp/experiments")
    parser.add_argument("--output", default="paper/fgcs/figures")
    args = parser.parse_args()
    root = ROOT / args.input
    output = ROOT / args.output
    write(output / "execution-semantics.tex", EXECUTION)
    write(output / "evidence-chain.tex", EVIDENCE)
    write(output / "sensitivity-effects.tex", sensitivity(json.loads((root / "sensitivity/results.json").read_text())))
    write(output / "energy-tradeoffs.tex", energy(json.loads((root / "energy/results.json").read_text())))
    print("FGCS_FIGURES_WRITTEN", output)


if __name__ == "__main__":
    main()
