#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("clean", Path(__file__).with_name("reproduce-fgcs-clean.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CleanReferenceTest(unittest.TestCase):
    def test_relative_reference_is_allowed_and_normalized(self):
        self.assertEqual("paper/tmp/fgcs-round2", MODULE.checked_reference("paper/tmp/fgcs-round2"))
        self.assertEqual("paper/tmp/fgcs-round2", MODULE.checked_reference("paper//tmp//fgcs-round2"))

    def test_absolute_and_parent_escape_references_are_rejected(self):
        for value in ("/tmp/reference", "../reference", "paper/../../reference", ""):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    MODULE.checked_reference(value)


if __name__ == "__main__":
    unittest.main()
