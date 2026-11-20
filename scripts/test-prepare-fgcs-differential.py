#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("prepare", Path(__file__).with_name("prepare-fgcs-differential.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PrepareDifferentialTest(unittest.TestCase):
    def test_output_layout_requires_relative_nonescaping_paths(self):
        self.assertEqual(Path("paper/tmp/fgcs-round2/differential"),
                         MODULE.relative_path("paper/tmp/fgcs-round2/differential", "output"))
        for value in ("/tmp/reference", "../reference", "paper/../../reference", ""):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    MODULE.relative_path(value, "output")


if __name__ == "__main__":
    unittest.main()
