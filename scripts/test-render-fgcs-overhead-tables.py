#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("tables", Path(__file__).with_name("render-fgcs-overhead-tables.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class OverheadTablesTest(unittest.TestCase):
    def rows(self):
        result = []
        for size in (50, 1000, 5000):
            for mode in ("off", "events", "lifecycle"):
                row = {"logicalTasks": size, "mode": mode}
                for field in ("simulationNanos", "serializationAndSizeAccountingNanos", "javaValidationNanos",
                              "pythonValidationNanos", "artifactBytes", "sampledTotalHeapPeakBytes"):
                    for suffix in ("Median", "Q1", "Q3"):
                        row[field + suffix] = None if mode == "events" and field == "pythonValidationNanos" else 1000000
                result.append(row)
        return result

    def test_render_has_two_tables_all_cells_and_na(self):
        with tempfile.TemporaryDirectory() as directory:
            summary = Path(directory) / "summary.json"
            summary.write_text(__import__("json").dumps(self.rows()))
            text = MODULE.render(summary)
        self.assertEqual(2, text.count("\\begin{table*}"))
        for size in (50, 1000, 5000):
            for mode in ("OFF", "EVENTS", "LIFECYCLE"):
                self.assertEqual(2, text.count(f"{size} & {mode} &"))
        self.assertIn("NA", text)
        self.assertIn("1.00", text)
        self.assertNotIn("tikzpicture", text)

    def test_main_replaces_the_old_table_without_retaining_stale_text(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            summary = root / "summary.json"
            summary.write_text(__import__("json").dumps(self.rows()))
            manuscript = root / "section.tex"
            manuscript.write_text("\\subsection{Evidence-Collection and Validation Overhead}\n"
                                  "\\begin{table*}old table\\end{table*}\nold trailing text\n")
            import sys
            old_args = sys.argv
            sys.argv = ["render", "--summary", str(summary), "--manuscript", str(manuscript)]
            try:
                MODULE.main()
            finally:
                sys.argv = old_args
            text = manuscript.read_text()
            self.assertNotIn("old table", text)
            self.assertNotIn("old trailing text", text)
            self.assertEqual(2, text.count("\\begin{table*}"))

    def test_missing_cell_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            summary = Path(directory) / "summary.json"
            summary.write_text("[]")
            with self.assertRaisesRegex(ValueError, "nine overhead cells"):
                MODULE.render(summary)


if __name__ == "__main__":
    unittest.main()
