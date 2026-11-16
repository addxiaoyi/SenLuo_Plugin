import unittest
import tempfile
from pathlib import Path
from zipfile import ZipFile

from check_independence import _single_match, check
from import_sources import load_module_specs


class IndependenceTests(unittest.TestCase):
    def test_source_metadata_is_independent(self):
        report = check()
        self.assertEqual([], report["errors"])
        self.assertEqual(4, report["modules"])

    def test_artifact_versions_are_discovered_without_a_version_constant(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = root / "KaleidoscopeDoll-2.0.0.jar"
            first.write_bytes(b"jar")
            path, error = _single_match((root,), "KaleidoscopeDoll-*.jar")
            self.assertEqual(first, path)
            self.assertIsNone(error)
            (root / "KaleidoscopeDoll-2.1.0.jar").write_bytes(b"jar")
            path, error = _single_match((root,), "KaleidoscopeDoll-*.jar")
            self.assertIsNone(path)
            self.assertIn("ambiguous", error)

    def test_plugin_jars_use_the_gradle_plugin_suffix(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, specs, _ = load_module_specs()
            for spec in specs:
                module = spec["module"]
                metadata = root / module / "src/main/resources/plugin.yml"
                metadata.parent.mkdir(parents=True)
                metadata.write_bytes((Path(__file__).resolve().parents[1] / module
                                      / "src/main/resources/plugin.yml").read_bytes())
                jar = root / f"Kaleidoscope{module.capitalize()}Plugin-0.1.0-dev.jar"
                with ZipFile(jar, "w") as archive:
                    archive.writestr("plugin.yml", metadata.read_bytes())
            report = check(source_root=root, artifacts=root)
            self.assertEqual([], report["errors"])


if __name__ == "__main__":
    unittest.main()
