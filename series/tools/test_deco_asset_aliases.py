import unittest

from deco_asset_aliases import add_missing_meal_gui_aliases, add_moved_asset_aliases


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

    def test_missing_meal_gui_alias_uses_only_the_matching_source_state_and_preserves_existing_model(self):
        meal = 'kaleidoscope_cookery/items/meals/gui/frogspawn_jelly.json'
        source = 'kaleidoscope_cookery/models/meals/block/frogspawn_jelly/0.json'
        alias = 'kaleidoscope_cookery/models/meals/gui/frogspawn_jelly.json'
        assets = {meal: b'{}', source: b'{"elements":[{}],"display":{"gui":{}}}'}
        self.assertEqual([{'alias': alias, 'source': source}], add_missing_meal_gui_aliases(assets))
        self.assertEqual(assets[source], assets[alias])

        custom = b'{"elements":[],"display":{"gui":{}}}'
        assets[alias] = custom
        self.assertEqual([], add_missing_meal_gui_aliases(assets))
        self.assertEqual(custom, assets[alias])

    def test_missing_meal_gui_alias_requires_the_matching_source_state(self):
        assets = {'kaleidoscope_cookery/items/meals/gui/missing.json': b'{}',
                  'kaleidoscope_cookery/models/meals/block/other/0.json': b'{}'}
        self.assertEqual([], add_missing_meal_gui_aliases(assets))
        self.assertNotIn('kaleidoscope_cookery/models/meals/gui/missing.json', assets)
