import unittest

from deco_interactions import function_sounds, item_tag_sources, link_variants, rules, selected_model, tag_inputs


class InteractionTests(unittest.TestCase):
    def test_tulip_extraction_and_decoration_form_a_paid_cycle(self):
        decorate = {"events": {"right_click": {"criteria": [{"event": "trans",
                    "item": {"id": "#demo:tulips"}, "args": {"index": "kaleidoscope_bar:blackboard/9"}}]}}}
        pending = []
        parsed = rules(decorate, {}, pending, "kaleidoscope_bar:blackboard/0", "bar",
                       {"demo:tulips": {"bar": {"values": ["red_tulip", "orange_tulip", "white_tulip", "pink_tulip"]}}})
        self.assertEqual(1, parsed[0]["cost"])
        extract = {"events": {"right_click": {"criteria": [{"event": "group", "item": {"id": "shears"},
                   "args": {"events": [{"event": "custom", "args": {"func": "kaleidoscope:bar/events/tulip_loot"}},
                                       {"event": "trans", "args": {"index": "kaleidoscope_bar:blackboard/0"}}]}}]}}}
        parsed = rules(extract, {}, pending, "kaleidoscope_bar:blackboard/9")
        self.assertFalse(parsed[0]["pending"])
        self.assertEqual(4, len(parsed[0]["rewards"]))
        self.assertEqual(0, parsed[0]["cost"])
        self.assertEqual("kaleidoscope_deco:kaleidoscope_bar/blackboard/0", parsed[0]["target"])
        self.assertEqual([], pending)

    def test_loot_without_a_transition_cannot_repeat_forever(self):
        definition = {"events": {"right_click": {"fallback": {"event": "custom",
                      "args": {"func": "kaleidoscope:bar/events/custom_loot", "item": "rose_bush"}}}}}
        pending = []
        parsed = rules(definition, {}, pending, "kaleidoscope_bar:blackboard/4")
        self.assertTrue(parsed[0]["pending"])
        self.assertEqual([], parsed[0]["rewards"])

    def test_custom_tag_uses_its_module_and_does_not_guess_conflicting_overlays(self):
        tags = item_tag_sources({
            "src/cookery/data/demo/tags/item/fruit.json": b'{"values":["apple","#minecraft:flowers"]}',
            "src/doll/data/demo/tags/item/fruit.json": b'{"values":[{"id":"sweet_berries","required":false}]}',
        })
        self.assertEqual(["#minecraft:flowers", "minecraft:apple"], tag_inputs("demo:fruit", "cookery", tags))
        self.assertEqual(["minecraft:sweet_berries"], tag_inputs("demo:fruit", "doll", tags))
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            tag_inputs("demo:fruit", "other", tags)

    def test_unported_custom_tag_action_leaves_fallback_reachable(self):
        definition = {"events": {"right_click": {
            "criteria": [{"event": "custom", "item": {"id": "#demo:fruit"}, "args": {"func": "demo:store"}}],
            "fallback": {"event": "sound", "args": {"sound": "block.wood.place"}},
        }}}
        pending = []
        parsed = rules(definition, {}, pending, "demo:chair", "other", {"demo:fruit": {"other": {"values": ["apple"]}}})
        self.assertEqual(1, len(parsed))
        self.assertEqual(["minecraft:block.wood.place"], parsed[0]["sounds"])
        self.assertIn("demo:store", pending[0])

    def test_only_presentation_functions_can_be_used_as_simple_transitions(self):
        files = {"src/example/data/demo/function/simple.mcfunction": b"playsound block.wood.place block @a\nparticle flame ~ ~ ~ 0 0 0 0 1"}
        self.assertEqual(["minecraft:block.wood.place"], function_sounds(files, "demo:simple"))
        files["src/example/data/demo/function/simple.mcfunction"] += b"\nclear @a diamond"
        with self.assertRaises(ValueError):
            function_sounds(files, "demo:simple")

    def test_unknown_side_effect_blocks_the_entire_transition(self):
        definition = {"events": {"right_click": {"fallback": {"event": "trans", "args": {"index": "other", "func": "demo:unknown"}}}}}
        pending = []
        parsed = rules(definition, {}, pending, "first")
        self.assertTrue(parsed[0]["pending"])
        self.assertEqual("", parsed[0]["target"])
        self.assertEqual(1, len(pending))

    def test_dye_model_selects_color_and_form_without_client_components(self):
        base = {"type": "model", "model": "demo:plain"}
        model = {"type": "select", "property": "custom_model_data", "index": 0, "fallback": base,
                 "cases": [{"when": "red", "model": {"type": "condition", "property": "custom_model_data", "index": 0,
                            "on_true": {"type": "model", "model": "demo:red_short"}, "on_false": base}}]}
        self.assertEqual("demo:red_short", selected_model(model, ["red"], [True])["model"])
        self.assertEqual("demo:plain", selected_model(model, ["blue"], [True])["model"])

    def test_variant_graph_handles_cycles_and_rejects_absent_targets(self):
        entries = [{"id": "demo:a", "rules": [{"target": "demo:b", "pending": False}]},
                   {"id": "demo:b", "rules": [{"target": "demo:a", "pending": False}, {"target": "demo:missing", "pending": False}]}]
        furniture = {key: {"variants": {"ground": {"elements": [{"item": key}]}}} for key in ("demo:a", "demo:b")}
        pending = []
        link_variants(entries, furniture, pending)
        self.assertIn("demo/b", furniture["demo:a"]["variants"])
        self.assertTrue(entries[1]["rules"][1]["pending"])
        self.assertEqual(1, len(pending))


if __name__ == "__main__":
    unittest.main()
