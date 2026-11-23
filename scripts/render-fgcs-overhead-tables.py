#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

MODES = ("off", "events", "lifecycle")
SIZES = (50, 1000, 5000)


def ordered(rows):
    return sorted(rows, key=lambda row: (SIZES.index(row["logicalTasks"]), MODES.index(row["mode"])))


def ms(row, field):
    values = [row[field + suffix] for suffix in ("Median", "Q1", "Q3")]
    if any(value is None for value in values):
        return "NA"
    return f"{values[0] / 1e6:.3f} ({values[1] / 1e6:.3f}--{values[2] / 1e6:.3f})"


def mb(row, field, iqr=False):
    if not iqr:
        return f"{row[field + 'Median'] / 1e6:.2f}"
    values = [row[field + suffix] for suffix in ("Median", "Q1", "Q3")]
    return f"{values[0] / 1e6:.2f} ({values[1] / 1e6:.2f}--{values[2] / 1e6:.2f})"


def row_line(row):
    return " & ".join([
        str(row["logicalTasks"]), row["mode"].upper(), ms(row, "simulationNanos"),
        ms(row, "serializationAndSizeAccountingNanos"), mb(row, "artifactBytes"),
        mb(row, "sampledTotalHeapPeakBytes", True),
    ]) + " \\\\"


def validation_line(row):
    return " & ".join([
        str(row["logicalTasks"]), row["mode"].upper(), ms(row, "javaValidationNanos"),
        ms(row, "pythonValidationNanos"),
    ]) + " \\\\"


def render(summary):
    rows = ordered(json.loads(Path(summary).read_text(encoding="utf-8")))
    if len(rows) != 9 or {(row["logicalTasks"], row["mode"]) for row in rows} != {
            (size, mode) for size in SIZES for mode in MODES}:
        raise ValueError("expected all nine overhead cells")
    production = """\\begin{table*}[t]
\\centering
\\caption{Evidence production overhead: median (Q1--Q3) over five fresh-JVM trials. Times are milliseconds; bundle and sampled heap are MB}
\\label{tab:overhead-results}
\\scriptsize
\\setlength{\\tabcolsep}{2pt}
\\begin{tabular}{@{}rlrrrr@{}}
\\toprule
Tasks & Mode & Simulation & Serialize & Bundle & Sampled heap \\\\
\\midrule
"""
    production += "\n\\addlinespace[2pt]\n".join(
        "\n".join(row_line(row) for row in rows[index:index + 3]) for index in (0, 3, 6))
    production += "\n\\bottomrule\n\\end{tabular}\n\\end{table*}\n"
    validation = """\\begin{table*}[t]
\\centering
\\caption{Serialized validation overhead: median (Q1--Q3) over five fresh-JVM trials, in milliseconds. Python is NA for event-only bundles}
\\label{tab:validation-overhead-results}
\\scriptsize
\\setlength{\\tabcolsep}{2pt}
\\begin{tabular}{@{}rlrr@{}}
\\toprule
Tasks & Mode & Java validation & Python validation \\\\
\\midrule
"""
    validation += "\n\\addlinespace[2pt]\n".join(
        "\n".join(validation_line(row) for row in rows[index:index + 3]) for index in (0, 3, 6))
    validation += "\n\\bottomrule\n\\end{tabular}\n\\end{table*}\n"
    lifecycle = next(row for row in rows if row["logicalTasks"] == 5000 and row["mode"] == "lifecycle")
    text = (f"\nAt 5000 tasks, lifecycle serialization takes {ms(lifecycle, 'serializationAndSizeAccountingNanos').split(' ')[0]}~ms, "
            f"serialized Java validation {ms(lifecycle, 'javaValidationNanos').split(' ')[0]}~ms, "
            f"and serialized Python validation {ms(lifecycle, 'pythonValidationNanos').split(' ')[0]}~ms; "
            f"the complete bundle is {mb(lifecycle, 'artifactBytes')}~MB. These measurements expose costs that "
            "the earlier in-memory audit alone could not describe. Simulation medians are not monotonic in "
            "recording mode, so we do not infer a universal recording-time ranking. The lifecycle heap sample "
            f"also has a wide interquartile range ({mb(lifecycle, 'sampledTotalHeapPeakBytes', True)}~MB), and is "
            "reported as variability rather than as a memory requirement. All modes use the same derived input, "
            "so raw-versus-derived input is not a within-cell confound; these are not raw-DAX equivalence results.\n")
    return production + "\n" + validation + "\n" + text


def main():
    parser = argparse.ArgumentParser(description="Render FGCS overhead tables directly from measured results")
    parser.add_argument("--summary", required=True)
    parser.add_argument("--manuscript", required=True)
    parser.add_argument("--output")
    args = parser.parse_args()
    tables = render(args.summary)
    if args.output:
        Path(args.output).write_text(tables, encoding="utf-8")
        return
    path = Path(args.manuscript)
    text = path.read_text(encoding="utf-8")
    start = text.index("\\begin{table*}", text.index("\\subsection{Evidence-Collection and Validation Overhead}"))
    end = text.index("\\end{table*}", start) + len("\\end{table*}")
    path.write_text(text[:start] + tables, encoding="utf-8")
    print("FGCS_OVERHEAD_TABLES_RENDERED", len(tables.splitlines()), "lines")


if __name__ == "__main__":
    main()
