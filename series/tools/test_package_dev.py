import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

from package_dev import load_module_packs, validate_runtime_reports, BASE_RUNTIME_REPORTS


class PackageDevTests(unittest.TestCase):
    def test_runtime_reports_require_every_named_probe_and_explicit_success(self):
        names = sorted(BASE_RUNTIME_REPORTS | {'deco-model-smoke.json'})
        evidence = {'requiredReports': names}
        run = {'reports': {name: {'failures': []} for name in names}}
        validate_runtime_reports(evidence, run)
        del run['reports']['deco-model-smoke.json']
        with self.assertRaises(ValueError): validate_runtime_reports(evidence, run)
        for bad in ({}, {'failures': None}, {'failures': ['missing furniture']}):
            run['reports']['deco-model-smoke.json'] = bad
            with self.assertRaises(ValueError): validate_runtime_reports(evidence, run)

    def test_runtime_report_extension_cannot_weaken_original_checks(self):
        run = {'reports': {name: {'failures': []} for name in BASE_RUNTIME_REPORTS}}
        validate_runtime_reports({}, run)
        for required in ([], ['deco-model-smoke.json'], list(BASE_RUNTIME_REPORTS) * 2, 'food-smoke.json'):
            with self.assertRaises(ValueError): validate_runtime_reports({'requiredReports': required}, run)

    def test_load_module_packs_requires_four_independent_plugin_packs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for module in ("doll", "tavern", "deco", "music"):
                with ZipFile(root / f"{module}-ce-pack-0.1.0-dev.zip", "w", ZIP_DEFLATED) as archive:
                    archive.writestr("pack.yml", module.encode())
                    archive.writestr("configuration/content.yml", b"{}")
            merged, archives = load_module_packs(root)
            self.assertEqual(4, len(archives))
            self.assertEqual(b"doll", merged["doll/pack.yml"])
            self.assertEqual(b"{}", merged["music/configuration/content.yml"])


if __name__ == "__main__":
    unittest.main()
