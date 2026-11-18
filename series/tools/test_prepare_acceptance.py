import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile, ZipInfo
import json
import hashlib

from check_acceptance_matrix import load as load_matrix
from import_sources import load_module_specs
from prepare_independent_servers import _within, install_artifact, prepare, required_artifacts, verify_zip


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

    def test_both_instances_have_unpacked_module_shared_and_cookery_pack_roots(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sources, artifacts, packs = (root / name for name in ("suite", "jars", "packs"))
            artifacts.mkdir()
            packs.mkdir()
            _, specs, _ = load_module_specs()
            for spec in specs:
                module = spec["module"]
                metadata = sources / module / "src/main/resources/plugin.yml"
                metadata.parent.mkdir(parents=True)
                metadata.write_bytes((Path(__file__).resolve().parents[1] / module
                                      / "src/main/resources/plugin.yml").read_bytes())
                with ZipFile(artifacts / f"Kaleidoscope{module.capitalize()}-2.0.0.jar", "w") as archive:
                    archive.writestr("plugin.yml", metadata.read_bytes())
                with ZipFile(packs / f"{module}-ce-pack-2.0.0.zip", "w") as archive:
                    archive.writestr("pack.yml", json.dumps({"namespace": spec["packNamespace"]}))
                    archive.writestr("configuration/content.yml", "{}\n")
                    archive.writestr(f"resourcepack/assets/test/models/{module}.json", "{}\n")
            with ZipFile(packs / "shared-ce-pack-2.0.0.zip", "w") as archive:
                archive.writestr("pack.yml", '{"namespace":"kseries_shared"}\n')
                archive.writestr("resourcepack/assets/test/atlases/blocks.json", '{"sources":[]}\n')
            cookery = root / "cookery.zip"
            with ZipFile(cookery, "w") as archive:
                archive.writestr("cookery_kitchen/pack.yml", "namespace: kaleidoscopecookery\n")
                archive.writestr("cookery_kitchen/configuration/kitchen.yml", "{}\n")
                archive.writestr("manifest.json", "{}\n")
            destination = root / "build/acceptance/runtime"
            engine = root / "craft-engine.jar"
            engine_config = "block:\n  serverside-blocks: 2000\nresource-pack:\n  cache-resource-files: true\nmisc:\n  delay-configuration-load: true\n"
            with ZipFile(engine, "w") as archive:
                archive.writestr("plugin.yml", "name: CraftEngine\n")
                archive.writestr("config.yml", engine_config)
            base_resources = root / "base-resources"
            stale_kitchen = base_resources / "cookery_kitchen/configuration/kitchen.yml"
            stale_kitchen.parent.mkdir(parents=True)
            stale_kitchen.write_text("stale kitchen\n")
            default = base_resources / "default_assets/pack.yml"
            default.parent.mkdir(parents=True)
            default.write_text("namespace: default\n")
            with patch("prepare_independent_servers.WORKSPACE", root), \
                    patch("prepare_independent_servers.SUITE", sources):
                report = prepare(destination, artifacts, packs, cookery_pack=cookery,
                                 craft_engine=engine, server_blocks=3000, base_resources=base_resources, early_pack_load=True)
            config_sha = hashlib.sha256(engine_config.replace("2000", "3000").replace(": true", ": false").encode()).hexdigest()
            self.assertEqual(config_sha, report["craftEngineConfig"]["sha256"])
            prepared_config = destination / "artifacts" / report["craftEngineConfig"]["path"]
            self.assertEqual(config_sha, hashlib.sha256(prepared_config.read_bytes()).hexdigest())
            for platform in ("paper", "folia"):
                resources = destination / platform / "plugins/CraftEngine/resources"
                for module in ("doll", "tavern", "deco", "music"):
                    self.assertTrue((resources / module / "pack.yml").is_file(), f"{platform}/{module}")
                    self.assertEqual("{}\n", (resources / module / "configuration/content.yml").read_text())
                    self.assertTrue((resources / module / f"resourcepack/assets/test/models/{module}.json").is_file())
                self.assertTrue((resources / "shared/pack.yml").is_file())
                self.assertTrue((resources / "cookery_kitchen/configuration/kitchen.yml").is_file())
                self.assertEqual("{}\n", (resources / "cookery_kitchen/configuration/kitchen.yml").read_text())
                self.assertTrue((resources / "default_assets/pack.yml").is_file())
                self.assertEqual([], list(resources.glob("*.zip")))
                self.assertEqual(6, len(report["instances"][platform]["cePacks"]))
                config = destination / platform / "plugins/CraftEngine/config.yml"
                self.assertEqual(engine_config.replace("2000", "3000").replace(": true", ": false"), config.read_text())
                self.assertEqual(3000, report["craftEngineConfig"]["serverBlocks"])
                self.assertEqual(config_sha, hashlib.sha256(config.read_bytes()).hexdigest())

    def test_stale_base_module_cannot_replace_pack_or_create_acceptance_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifacts, packs = root / "jars", root / "packs"
            artifacts.mkdir()
            packs.mkdir()
            for module in ("doll", "tavern", "deco", "music"):
                with ZipFile(artifacts / f"Kaleidoscope{module.capitalize()}-2.0.0.jar", "w") as archive:
                    archive.writestr("plugin.yml", "name: Test\n")
                with ZipFile(packs / f"{module}-ce-pack-2.0.0.zip", "w") as archive:
                    archive.writestr("pack.yml", "namespace: test\n")
                    archive.writestr("configuration/content.yml", "fresh content\n")
            stale = root / "base-resources/doll/configuration/content.yml"
            stale.parent.mkdir(parents=True)
            stale.write_text("stale content\n")
            destination = root / "build/acceptance/runtime"
            with patch("prepare_independent_servers.WORKSPACE", root), \
                    patch("prepare_independent_servers.check_independence", return_value={"errors": []}):
                with self.assertRaisesRegex(ValueError, "base resources.*doll"):
                    prepare(destination, artifacts, packs, base_resources=root / "base-resources")
            self.assertFalse(destination.exists())
            self.assertEqual("stale content\n", stale.read_text())

    def test_unpacked_ce_archives_cannot_write_outside_the_pack_root(self):
        for name in ("../outside", "/outside", "C:/outside", "nested\\outside", "nested/../../outside"):
            with self.subTest(member=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive = root / "unsafe.zip"
                with ZipFile(archive, "w") as output:
                    output.writestr("pack.yml", "namespace: test\n")
                    output.writestr("configuration/content.yml", "{}\n")
                    # ZipInfo normalizes Windows separators during construction.
                    member = ZipInfo()
                    member.filename = name
                    output.writestr(member, "bad")
                with self.assertRaises(ValueError):
                    install_artifact(root, "plugins/CraftEngine/resources/test", archive, "ce-pack")
                self.assertFalse((root / "outside").exists())


if __name__ == "__main__":
    unittest.main()
