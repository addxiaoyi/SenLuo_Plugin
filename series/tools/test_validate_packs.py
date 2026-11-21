import unittest
import json
from validate_packs import validate


def encoded(value):
    return json.dumps(value).encode()


class PackValidationTests(unittest.TestCase):
    def pack(self, texture='test:valid'):
        return {'sample/configuration/content.yml': encoded({'items': {'test:item': {
            'model': {'type': 'minecraft:model', 'path': 'test:child'}}}}),
            'sample/resourcepack/assets/test/models/child.json': encoded({
                'parent': 'test:parent', 'textures': {'layer0': texture}}),
            'sample/resourcepack/assets/test/models/parent.json': encoded({'textures': {'layer0': 'test:missing'}}),
            'sample/resourcepack/assets/test/textures/valid.png': b'fixture'}

    def test_child_texture_override_replaces_missing_parent_default(self):
        self.assertEqual([], validate(self.pack())['errors'])

    def test_missing_effective_texture_is_an_error(self):
        errors = validate(self.pack('test:absent'))['errors']
        self.assertEqual(1, len(errors))
        self.assertIn('textures/absent.png', errors[0])

    def test_conflicting_atlases_are_not_silently_overwritten(self):
        files = self.pack()
        files['sample/resourcepack/assets/minecraft/atlases/blocks.json'] = encoded({'sources': []})
        files['other/resourcepack/assets/minecraft/atlases/blocks.json'] = encoded({'sources': [{'type': 'single'}]})
        self.assertIn('Conflicting shared resource: minecraft/atlases/blocks.json', validate(files)['errors'])

    def test_standalone_module_pack_is_validated_without_aggregate_prefix(self):
        files = {path.split('/', 1)[1]: content for path, content in self.pack().items()}
        self.assertEqual([], validate(files)['errors'])

    def test_exported_orphan_item_dependencies_are_reported_without_hiding_reachable_errors(self):
        files = self.pack()
        files['sample/resourcepack/assets/test/items/orphan.json'] = encoded({
            'model': {'type': 'minecraft:model', 'model': 'test:absent'}})
        report = validate(files)
        self.assertEqual([], report['errors'])
        self.assertTrue(any('models/absent.json' in warning for warning in report['asset_warnings']))
        self.assertEqual(2, report['reachable_custom_models'])
        report = validate({**files, **self.pack('test:missing_live')})
        self.assertTrue(any('missing_live.png' in error for error in report['errors']))
        self.assertFalse(any('missing_live.png' in warning for warning in report['asset_warnings']))


if __name__ == '__main__':
    unittest.main()
