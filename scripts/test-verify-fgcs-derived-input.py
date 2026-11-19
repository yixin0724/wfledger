#!/usr/bin/env python3
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("derived_verify", Path(__file__).with_name("verify-fgcs-derived-input.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

SOURCE = """<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\">
<job id=\"a\" runtime=\"1\"><uses file=\"x\" link=\"output\" size=\"2\"/></job>
<job id=\"b\" runtime=\"2\"><uses file=\"x\" link=\"output\" size=\"4\"/></job>
<job id=\"c\" runtime=\"3\"><uses file=\"x\" link=\"input\" size=\"2\"/></job>
<child ref=\"c\"><parent ref=\"a\"/><parent ref=\"b\"/></child>
</adag>"""
DERIVED = """<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\">
<job id=\"a\" runtime=\"1\"><uses file=\"x__producer_a\" link=\"output\" size=\"2\"/></job>
<job id=\"b\" runtime=\"2\"><uses file=\"x__producer_b\" link=\"output\" size=\"4\"/></job>
<job id=\"c\" runtime=\"3\"><uses file=\"x__producer_a\" link=\"input\" size=\"2\"/><uses file=\"x__producer_b\" link=\"input\" size=\"4\"/></job>
<child ref=\"c\"><parent ref=\"a\"/><parent ref=\"b\"/></child>
</adag>"""


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


class DerivedInputVerifierTest(unittest.TestCase):
    def write_fixture(self, root, derived=DERIVED):
        source = root / "source.dax"
        target = root / "derived.dax"
        sidecar = root / "derived.json"
        source.write_text(SOURCE)
        target.write_text(derived)
        sidecar.write_text(json.dumps({
            "sourceSha256": digest(SOURCE),
            "derivedSha256": digest(derived),
            "rawInputEquivalenceClaimed": False,
            "dependencyEdgeCount": 2,
            "multiProducerInputExpansions": 1,
            "additionalInputDeclarations": 1,
        }))
        return source, target, sidecar

    def test_namespace_and_explicit_multi_producer_expansion_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            result = MODULE.verify(*self.write_fixture(Path(directory)))
            self.assertEqual("PASS", result["status"])
            self.assertEqual(2, result["edges"])
            self.assertEqual(1, result["multiProducerInputExpansions"])

    def test_runtime_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            changed = DERIVED.replace('id="c" runtime="3"', 'id="c" runtime="4"')
            with self.assertRaisesRegex(ValueError, "runtime changed"):
                MODULE.verify(*self.write_fixture(Path(directory), changed))

    def test_empty_namespace_document_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, target, sidecar = self.write_fixture(root)
            target.write_text("<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\"/>")
            sidecar.write_text(json.dumps({
                "sourceSha256": digest(SOURCE),
                "derivedSha256": digest(target.read_text()),
                "rawInputEquivalenceClaimed": False,
            }))
            with self.assertRaisesRegex(ValueError, "empty workflow"):
                MODULE.verify(source, target, sidecar)


if __name__ == "__main__":
    unittest.main()
