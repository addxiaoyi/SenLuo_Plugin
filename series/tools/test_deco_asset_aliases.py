import unittest

from deco_asset_aliases import add_moved_asset_aliases


class MovedAssetTests(unittest.TestCase):
    def test_exact_model_alias_keeps_relative_texture_path_and_animated_texture_metadata(self):
        model = b'{"parent":"item/generated","textures":{"layer0":"kaleidoscope_cookery:item/mysterious"}}'
        current = 'kaleidoscope_cookery/models/meals/item/mysterious.json'
        texture = 'kaleidoscope_cookery/textures/item/mysterious.png'
        assets = {current: model, texture: b'png', texture + '.mcmeta': b'{"animation":{"frametime":2}}'}
        original = dict(assets)
        added = add_moved_asset_aliases(assets)
        self.assertEqual([{'alias': 'kaleidoscope_cookery/models/item/mysterious.json', 'source': current}], added)
        self.assertEqual(model, assets[added[0]['alias']])
        self.assertEqual(original, {key: assets[key] for key in original})

    def test_existing_definition_is_preserved_and_unknown_missing_assets_are_not_invented(self):
        assets = {'kaleidoscope_cookery/models/item/mysterious.json': b'override',
                  'kaleidoscope_cookery/models/meals/item/mysterious.json': b'source',
                  'kaleidoscope_cookery/models/meals/item/frogspawn_jelly.json': b'item'}
        original = dict(assets)
        self.assertEqual([], add_moved_asset_aliases(assets))
        self.assertEqual(original, assets)
        self.assertNotIn('kaleidoscope_cookery/models/meals/gui/frogspawn_jelly.json', assets)
