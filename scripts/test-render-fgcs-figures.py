#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("figures", Path(__file__).with_name("render-fgcs-figures.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FigureGenerationTest(unittest.TestCase):
    def sensitivity_rows(self):
        rows = []
        for workflow in ("Montage_50.dax", "CyberShake_50.dax", "Inspiral_50.dax"):
            for vms in (1, 2, 4, 8):
                base = workflow + "-v" + str(vms) + "-r2-b15-"
                rows.append({"label": base + "STATIC", "makespanSeconds": 100.0})
                rows.append({"label": base + "READY_BATCH_MINMIN", "makespanSeconds": 1400.0 if vms == 8 else 100.0})
        return rows

    def test_outlier_remains_inside_signed_log_axis(self):
        body = MODULE.sensitivity_figure(self.sensitivity_rows())
        self.assertIn("1000\\%", body)
        self.assertIn("Inspiral", body)
        self.assertNotIn("tikzpicture", body)

    def test_boxes_have_real_multiline_text(self):
        body = MODULE.box(0, 0, 30, 10, "first\\nsecond")
        self.assertIn("\\shortstack{first\\\\second}", body)
        self.assertIn("\\pgfusepath{stroke}", body)

    def test_blank_figure_is_rejected_before_compilation(self):
        with self.assertRaisesRegex(ValueError, "blank vector"):
            MODULE.compile_vector("blank", "\\begin{pgfpicture}\\end{pgfpicture}")

    def test_energy_figure_identifies_families_and_policies(self):
        rows = []
        for workflow in ("Montage_50", "CyberShake_50", "Sipht_60", "Inspiral_50", "Epigenomics_46"):
            rows.append({"label": workflow + ".dax-default", "makespanSeconds": 100.0, "energyJoules": 50.0})
            rows.append({"label": workflow + ".dax-MIN_POWER", "makespanSeconds": 120.0, "energyJoules": 25.0})
        body = MODULE.energy_figure(rows)
        for family in ("Montage", "CyberShake", "SIPHT", "Inspiral", "Epigenomics"):
            self.assertIn(family, body)
        self.assertIn("MIN\\_POWER", body)
        self.assertIn("policy_marker", MODULE.policy_marker.__name__)
        self.assertIn("Makespan / default HEFT", body)


if __name__ == "__main__":
    unittest.main()
