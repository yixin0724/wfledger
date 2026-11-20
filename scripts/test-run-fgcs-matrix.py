#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("fgcs_matrix", Path(__file__).with_name("run-fgcs-matrix.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FgcsMatrixVerifierTest(unittest.TestCase):
    def overhead_row(self, workflow_index, selected, repetition):
        workflow = MODULE.WORKFLOWS[workflow_index]
        order = list(MODULE.MODES[(workflow_index + repetition) % 3:] + MODULE.MODES[:(workflow_index + repetition) % 3])
        position = order.index(selected)
        off = selected == "off"
        lifecycle = selected == "lifecycle"
        event_only = selected == "events"
        return {
            "label": f"{workflow}-{selected}-r{repetition}",
            "protocol": "FGCS_OVERHEAD_ROUND2_V2",
            "workflow": workflow,
            "mode": selected,
            "repetition": repetition,
            "workflowIndex": workflow_index,
            "modeOrderIndex": position,
            "modeOrder": order,
            "warmupCompleted": True,
            "normalizedInput": f"normalized/Montage_{50 if workflow_index == 0 else 1000 if workflow_index == 1 else 5000}.dax",
            "platformProfile": "FGC_STORAGE_4_VMS_1_HOST",
            "completed": True,
            "logicalTasks": 1,
            "completedTasks": 1,
            "energyResidual": 0.0,
            "costResidual": 0.0,
            "makespanResidual": 0.0,
            "tailResidual": 0.0,
            "dependencyResidual": 0.0,
            "makespanSeconds": 10.0,
            "planningAlgorithm": "RANDOM",
            "schedulingAlgorithm": "STATIC",
            "dataMovementModel": "COHERENT_STORAGE_DATAFLOW_V3",
            "fileSystem": "LOCAL",
            "randomSeed": 7,
            "vmCount": 4,
            "eventRecorderCap": 200000,
            "eventRecordingEnabled": not off,
            "networkEvidenceMode": "FILE_STORAGE_LIFECYCLE_V3" if lifecycle else "OFF",
            "eventCount": 0 if off else 4,
            "artifactBytes": 100,
            "manifestBytes": 40,
            "metricsBytes": 40,
            "eventStreamBytes": 0 if off else 20,
            "lifecycleEvidenceBytes": 30 if lifecycle else 0,
            "lifecycleEvidenceAvailable": lifecycle,
            "simulationNanos": 100,
            "serializationAndSizeAccountingNanos": 20,
            "artifactWriteNanos": 20,
            "auditNanos": 10,
            "javaValidationApplicable": True,
            "javaValidationNanos": 30,
            "javaValidationStatus": "VALID",
            "javaValidationScope": "SERIALIZED_BUNDLE",
            "pythonValidationApplicable": not event_only,
            "pythonValidationNanos": None if event_only else 40,
            "pythonValidationStatus": "NA" if event_only else ("VALID_COMPLETE" if lifecycle else "DISABLED"),
            "pythonValidationScope": "NOT_APPLICABLE_EVENT_ONLY_BUNDLE_WITHOUT_LIFECYCLE_CAPTURE" if event_only else "SUPPORTED_SCOPE",
            "heapBeforeBytes": 10,
            "sampledTotalHeapPeakBytes": 50,
            "heapSampleCount": 3,
            "heapPoolPeakSumBytes": 60,
            "threadAllocatedBytes": 1000,
            "meanWaitingSeconds": None if off else 2.0,
            "meanTrueSlowdown": None if off else 3.0,
            "meanWaitingSecondsAvailable": not off,
            "meanTrueSlowdownAvailable": not off,
            "eventDependentMetricAvailability": "AVAILABLE" if not off else "UNAVAILABLE_EVENT_RECORDING_DISABLED",
            "totalWaitingTimeObservationCount": 0 if off else 1,
            "trueSlowdownObservationCount": 0 if off else 1,
        }

    def populate(self, root, repetitions=1):
        for name, count in MODULE.COUNTS.items():
            if name == "overhead":
                continue
            rows = []
            for index in range(count):
                rows.append({
                    "label": f"{name}-r{index}",
                    "completed": True,
                    "logicalTasks": 1,
                    "completedTasks": 1,
                    "energyResidual": 0.0,
                    "costResidual": 0.0,
                    "makespanResidual": 0.0,
                    "tailResidual": 0.0,
                    "dependencyResidual": 0.0,
                })
            path = root / name / "results.json"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(rows), encoding="utf-8")
        rows = []
        for workflow_index in range(len(MODULE.WORKFLOWS)):
            for repetition in range(repetitions):
                shift = (workflow_index + repetition) % len(MODULE.MODES)
                order = MODULE.MODES[shift:] + MODULE.MODES[:shift]
                for selected in order:
                    rows.append(self.overhead_row(workflow_index, selected, repetition))
        path = root / "overhead/results.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(rows), encoding="utf-8")

    def read_overhead(self, root):
        path = root / "overhead/results.json"
        return path, json.loads(path.read_text(encoding="utf-8"))

    def assert_rejected(self, root, pattern, repetitions=1):
        with self.assertRaisesRegex(ValueError, pattern):
            MODULE.verify_overhead(root, repetitions)

    def test_valid_matrix_passes_completion_recording_and_availability_invariants(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.populate(root)
            expected = sum(MODULE.COUNTS.values()) - MODULE.COUNTS["overhead"] + 9
            self.assertEqual(MODULE.verify(root, 1), expected)
            self.assertEqual(MODULE.verify_overhead(root, 1), 9)

    def test_default_overhead_protocol_remains_45_measured_runs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.populate(root, 5)
            self.assertEqual(MODULE.verify_overhead(root, 5), 45)
            self.assertEqual(MODULE.COUNTS["overhead"], 45)

    def test_off_run_with_retained_events_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.populate(root)
            path, rows = self.read_overhead(root)
            rows[0]["eventCount"] = 1
            path.write_text(json.dumps(rows), encoding="utf-8")
            self.assert_rejected(root, "OFF retained events")

    def test_off_without_common_bundle_or_with_zero_instead_of_na_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.populate(root)
            path, rows = self.read_overhead(root)
            rows[0]["artifactBytes"] = 0
            rows[0]["meanWaitingSeconds"] = 0.0
            path.write_text(json.dumps(rows), encoding="utf-8")
            self.assert_rejected(root, "common bundle|OFF event-dependent")

    def test_missing_timing_field_is_not_treated_as_zero_or_na(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.populate(root)
            path, rows = self.read_overhead(root)
            del rows[0]["javaValidationNanos"]
            path.write_text(json.dumps(rows), encoding="utf-8")
            self.assert_rejected(root, "missing javaValidationNanos")

    def test_event_only_python_measurement_must_be_explicit_na(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.populate(root)
            path, rows = self.read_overhead(root)
            events = next(row for row in rows if row["mode"] == "events")
            events["pythonValidationNanos"] = 12
            path.write_text(json.dumps(rows), encoding="utf-8")
            self.assert_rejected(root, "event-only Python")

    def test_supported_python_validation_cannot_be_silently_na(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.populate(root)
            path, rows = self.read_overhead(root)
            rows[0]["pythonValidationNanos"] = None
            path.write_text(json.dumps(rows), encoding="utf-8")
            self.assert_rejected(root, "Python validation")

    def test_enabled_modes_must_have_equal_event_dependent_metrics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.populate(root)
            path, rows = self.read_overhead(root)
            lifecycle = next(row for row in rows if row["mode"] == "lifecycle")
            lifecycle["meanTrueSlowdown"] = 99.0
            path.write_text(json.dumps(rows), encoding="utf-8")
            self.assert_rejected(root, "paired event-dependent")

    def test_mode_order_and_warmup_are_protocol_invariants(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.populate(root)
            path, rows = self.read_overhead(root)
            rows[0]["modeOrderIndex"] = 2
            rows[0]["warmupCompleted"] = False
            path.write_text(json.dumps(rows), encoding="utf-8")
            self.assert_rejected(root, "mode order|warmup")

    def test_ambiguous_pool_peak_name_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.populate(root)
            path, rows = self.read_overhead(root)
            rows[0]["peakHeapBytes"] = rows[0]["heapPoolPeakSumBytes"]
            path.write_text(json.dumps(rows), encoding="utf-8")
            self.assert_rejected(root, "ambiguous peakHeapBytes")

    def test_missing_matrix_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "missing"):
                MODULE.verify(Path(directory), 1)


if __name__ == "__main__":
    unittest.main()
