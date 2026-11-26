#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "fgcs_summary", Path(__file__).with_name("summarize-fgcs-results.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class OverheadSummaryTest(unittest.TestCase):
    def rows(self, workflow="Montage_50", repetitions=5):
        result = []
        for repetition in range(repetitions):
            for mode_index, selected in enumerate(MODULE.MODES):
                base = repetition * 100 + mode_index * 10
                row = {
                    "label": f"{workflow}-{selected}-r{repetition}",
                    "workflow": workflow,
                    "mode": selected,
                    "repetition": repetition,
                    "workflowIndex": 0,
                    "modeOrderIndex": mode_index,
                    "logicalTasks": 4,
                }
                for field in MODULE.FIELDS:
                    row[field] = base + repetition + 1
                row["simulationNanos"] = repetition + 1
                row["pythonValidationNanos"] = None if selected == "events" else repetition + 11
                row["pythonValidationStatus"] = "NA" if selected == "events" else "VALID_COMPLETE"
                row["pythonValidationScope"] = (
                    "NOT_APPLICABLE_EVENT_ONLY_BUNDLE_WITHOUT_LIFECYCLE_CAPTURE"
                    if selected == "events" else "SUPPORTED")
                result.append(row)
        return result

    def test_median_range_iqr_and_raw_trials_are_emitted(self):
        summary = MODULE.overhead(self.rows())
        off = next(row for row in summary if row["mode"] == "off")
        self.assertEqual(off["simulationNanosMedian"], 3)
        self.assertEqual(off["simulationNanosMin"], 1)
        self.assertEqual(off["simulationNanosMax"], 5)
        self.assertEqual(off["simulationNanosRange"], 4)
        self.assertEqual(off["simulationNanosQ1"], 2)
        self.assertEqual(off["simulationNanosQ3"], 4)
        self.assertEqual(off["simulationNanosIQR"], 2)
        self.assertEqual([trial["simulationNanos"] for trial in off["trials"]], [1, 2, 3, 4, 5])
        self.assertEqual(off["pythonValidationNanosMedian"], 13)
        events = next(row for row in summary if row["mode"] == "events")
        for suffix in ("Median", "Min", "Max", "Range", "Q1", "Q3", "IQR"):
            self.assertIsNone(events["pythonValidationNanos" + suffix])
        self.assertTrue(all(trial["pythonValidationNanos"] is None for trial in events["trials"]))

    def test_paired_deltas_preserve_raw_values_and_na_scope(self):
        rows = self.rows(repetitions=1)
        for row in rows:
            row["simulationNanos"] = {"off": 10, "events": 13, "lifecycle": 16}[row["mode"]]
        paired = MODULE.paired_deltas(rows)
        trials = paired["trials"]
        events_off = next(row for row in trials if row["comparison"] == "EVENTS_MINUS_OFF"
                          and row["field"] == "simulationNanos")
        self.assertEqual(events_off["baselineValue"], 10)
        self.assertEqual(events_off["treatmentValue"], 13)
        self.assertEqual(events_off["delta"], 3)
        lifecycle_off = next(row for row in trials if row["comparison"] == "LIFECYCLE_MINUS_OFF"
                             and row["field"] == "pythonValidationNanos")
        self.assertEqual(lifecycle_off["delta"], 0)
        unsupported = next(row for row in trials if row["comparison"] == "EVENTS_MINUS_OFF"
                           and row["field"] == "pythonValidationNanos")
        self.assertIsNone(unsupported["delta"])
        self.assertEqual(unsupported["scope"], "NA_EVENT_ONLY_PYTHON_VALIDATION_UNSUPPORTED")
        summary = next(row for row in paired["summary"] if row["comparison"] == "EVENTS_MINUS_OFF"
                       and row["field"] == "pythonValidationNanos")
        self.assertIsNone(summary["deltaMedian"])

    def test_missing_or_partially_na_fields_fail_explicitly(self):
        missing = self.rows()
        del missing[0]["simulationNanos"]
        with self.assertRaisesRegex(ValueError, "missing simulationNanos"):
            MODULE.overhead(missing)
        partial = self.rows()
        next(row for row in partial if row["mode"] == "off")["pythonValidationNanos"] = None
        with self.assertRaisesRegex(ValueError, "mixed numeric/NA"):
            MODULE.overhead(partial)


class SensitivitySummaryTest(unittest.TestCase):
    def test_tolerant_counts_and_raw_signed_counts_are_both_retained(self):
        pairs = ((10.0, 10.0), (10.0, 10.0000000005), (10.0, 9.9999999995),
                 (10.0, 10.000000002), (10.0, 9.999999998))
        rows = []
        for index, (heft, minmin) in enumerate(pairs):
            rows.append({"label": f"case-{index}-STATIC", "makespanSeconds": heft})
            rows.append({"label": f"case-{index}-READY_BATCH_MINMIN", "makespanSeconds": minmin})
        result = MODULE.sensitivity(rows)
        self.assertEqual(result["counts"], {"heft-lower": 1, "minmin-lower": 1, "tie": 3})
        self.assertEqual(result["rawSignedCounts"], {"heft-lower": 2, "minmin-lower": 2, "tie": 1})
        self.assertEqual(result["cells"][1]["relation"], "tie")
        self.assertEqual(result["cells"][1]["rawRelation"], "heft-lower")
        self.assertEqual(result["cells"][1]["heftMakespanSeconds"], pairs[1][0])
        self.assertEqual(result["cells"][1]["minMinMakespanSeconds"], pairs[1][1])
        self.assertEqual(result["cells"][1]["minMinMinusHeftSeconds"], pairs[1][1] - pairs[1][0])


if __name__ == "__main__":
    unittest.main()
