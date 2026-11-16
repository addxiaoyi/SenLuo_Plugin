import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from check_acceptance_matrix import load as load_matrix
from prepare_independent_servers import _within, required_artifacts, verify_zip


class AcceptancePreparationTests(unittest.TestCase):
    def test_matrix_keeps_all_five_modules_explicit(self):
        matrix = load_matrix()
        self.assertEqual({"cookery", "doll", "tavern", "deco", "music"},
                         {row["module"] for row in matrix["modules"]})

    def test_server_root_must_be_inside_acceptance_directory(self):
        parent = Path("/tmp/senluo-acceptance")
        self.assertTrue(_within(parent / "paper", parent))
        self.assertFalse(_within(Path("/tmp/senluo-prod"), parent))

    def test_archive_path_escape_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "unsafe.zip"
            with ZipFile(archive, "w") as output:
                output.writestr("../outside", b"bad")
            with self.assertRaises(ValueError):
                verify_zip(archive, ())

    def test_paper_plugin_descriptor_is_accepted_for_runtime_plugins(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "craft-engine.jar"
            with ZipFile(archive, "w") as output:
                output.writestr("paper-plugin.yml", "name: CraftEngine\n")
            verify_zip(archive, ("paper-plugin.yml",))

    def test_runtime_preparation_finds_gradle_plugin_jar_names(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifacts = root / "artifacts"
            packs = root / "packs"
            artifacts.mkdir()
            packs.mkdir()
            for module in ("doll", "tavern", "deco", "music"):
                suffix = "Plugin" if module == "doll" else ""
                jar = artifacts / f"Kaleidoscope{module.capitalize()}{suffix}-0.1.0-dev.jar"
                with ZipFile(jar, "w") as archive:
                    archive.writestr("plugin.yml", "name: Test\n")
                with ZipFile(packs / f"{module}-ce-pack-0.1.0-dev.zip", "w") as archive:
                    archive.writestr("pack.yml", "namespace: test\n")
                    archive.writestr("configuration/content.yml", "{}\n")
            found = required_artifacts(artifacts, packs, None, None, None)
            self.assertEqual(8, len(found))
            self.assertEqual(4, sum(kind == "plugin" for _, _, kind in found))


if __name__ == "__main__":
    unittest.main()
