#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("license_check", ROOT / "scripts/check-license-compliance.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class LicenseComplianceTest(unittest.TestCase):
    def fixture(self, root):
        (root / "licenses").mkdir(parents=True)
        for name in (
            "NOTICE.md", "THIRD_PARTY.md", "CITATION.cff", "README.md",
            "simulator/src/main/java/org/workflowsim/WorkflowPlanner.java",
            "simulator/src/main/java/org/cloudbus/cloudsim/Cloudlet.java",
        ):
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        shutil.copytree(ROOT / "licenses", root / "licenses", dirs_exist_ok=True)
        shutil.copyfile(ROOT / "LICENSE", root / "LICENSE")

    def test_current_provenance_and_notices_pass(self):
        self.assertEqual([], MODULE.check(ROOT))

    def test_missing_notice_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            (root / "NOTICE.md").unlink()
            issues = MODULE.check(root)
            self.assertIn("missing NOTICE.md", issues)

    def test_stale_project_license_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            path = root / "CITATION.cff"
            path.write_text(path.read_text().replace("GPL-3.0-or-later", "GPL-2.0-or-later"))
            issues = MODULE.check(root)
            self.assertIn("CITATION license metadata is not GPL-3.0-or-later", issues)
            self.assertIn("stale GPL-2.0-or-later metadata", issues)

    def test_changed_license_hash_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            path = root / "licenses/WorkflowSim-GTPL-notice.txt"
            path.write_text(path.read_text() + "\nmodified notice\n")
            self.assertIn("license hash mismatch for WorkflowSim-GTPL-notice.txt", MODULE.check(root))


if __name__ == "__main__":
    unittest.main()
