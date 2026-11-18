import unittest

from audit_upstream import AuditError
from cookery_recipes import RecipeSources, mapped, TARGET_TAG_MEMBERS


class TagFallbackTests(unittest.TestCase):
    def test_vanilla_members_are_explicit_and_do_not_hide_unknown_tags(self):
        source = RecipeSources({}, {}, {}, {})
        for tag, items in TARGET_TAG_MEMBERS.items():
            self.assertEqual(list(items), source.expand({'tag': tag}))
        self.assertEqual(['minecraft:brown_mushroom', 'minecraft:red_mushroom'], source.expand({'tag': 'forge:mushrooms'}))
        with self.assertRaises(AuditError):
            source.expand({'tag': 'forge:unknown'})

    def test_source_definitions_override_fallbacks_including_nested_tags(self):
        source = RecipeSources({}, {'forge:ingots/iron': {'values': ['minecraft:gold_ingot']},
                                    'demo:metal': {'values': ['#forge:ingots/iron']}}, {}, {})
        self.assertEqual(['minecraft:gold_ingot'], source.expand({'tag': 'demo:metal'}))

    def test_recipe_outputs_resolve_to_runtime_tool_ids(self):
        self.assertEqual('kaleidoscopecookery:kitchen_shovel_no_oil', mapped('kaleidoscope_cookery:kitchen_shovel'))
        self.assertEqual('kaleidoscopecookery:recipe_item_no_recipe', mapped('kaleidoscope_cookery:recipe_item'))

    def test_fence_alias_preserves_native_tags_and_source_overrides(self):
        source = RecipeSources({}, {}, {}, {})
        self.assertEqual(['#minecraft:wooden_fences'], source.expand({'tag': 'forge:fences/wooden'}, vanilla_tags=True))
        with self.assertRaises(AuditError):
            source.expand({'tag': 'forge:fences/wooden'})
        source.tags['forge:fences/wooden'] = {'values': ['minecraft:oak_fence']}
        self.assertEqual(['minecraft:oak_fence'], source.expand({'tag': 'forge:fences/wooden'}, vanilla_tags=True))

    def test_known_optional_forge_tag_is_resolved_instead_of_omitted(self):
        source = RecipeSources({}, {'demo:input': {'values': [
            {'id': '#forge:cobblestone', 'required': False},
            {'id': '#forge:fences/wooden', 'required': False}]}}, {}, {})
        omissions = []
        self.assertEqual(['minecraft:cobblestone', 'minecraft:infested_cobblestone',
                          'minecraft:mossy_cobblestone', 'minecraft:cobbled_deepslate', '#minecraft:wooden_fences'],
                         source.expand({'tag': 'demo:input'}, vanilla_tags=True, omissions=omissions))
        self.assertEqual([], omissions)
