"""Port only the fixed Deco wooden-chair/cook-stool family and its source recipes."""
from __future__ import annotations

import copy
import json

WOODS = ("acacia", "bamboo", "birch", "cherry", "crimson", "dark_oak", "jungle", "mangrove",
         "oak", "pale_oak", "spruce", "warped")
COLORS = ("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
          "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")
SOURCE = "src/kaleidoscope_cookery_plus/data/kaleidoscope/function/cookery_plus/index/table.mcfunction"
ASSET_NS = "kaleidoscope_cookery_plus"


def static_model(node: dict, strings: list[str]) -> dict:
    """Resolve every nested CMD selector, including the chair's composite cushion layer."""
    kind = node["type"].removeprefix("minecraft:")
    if kind == "composite":
        return {**copy.deepcopy(node), "models": [static_model(child, strings) for child in node["models"]]}
    if kind == "select" and node["property"].removeprefix("minecraft:") == "custom_model_data":
        index = node.get("index", 0)
        value = strings[index] if index < len(strings) else ""
        selected = node.get("fallback", {"type": "empty"})
        for case in node["cases"]:
            if value in (case["when"] if isinstance(case["when"], list) else [case["when"]]):
                selected = case["model"]
                break
        return static_model(selected, strings)
    if kind not in ("model", "empty"):
        raise ValueError(f"Unsupported Deco wooden-seat model branch: {kind}")
    return copy.deepcopy(node)


def append_seating(files: dict[str, bytes], original: dict[str, bytes], namespaces: set[str],
                   items: dict, furnishings: dict, entries: list,
                   model_tree, furniture, placeable) -> dict:
    """Generate explicit wood IDs; never infer a surviving recipe from an item-frame model alone."""
    source = files[SOURCE].decode("utf-8")
    for token in ('input.chair set value', 'input.cook_stool set value',
                  'func:"kaleidoscope:cookery_plus/events/carpets"',
                  'func:"kaleidoscope:cookery_plus/events/shear"', 'fallback:{event:"sit"}'):
        if token not in source:
            raise ValueError(f"Pinned Deco seating behavior changed: {token}")
    labels = json.loads(original.get("kaleidoscope/lang/zh_cn.json", b"{}"))
    recipes = {}
    for kind in ("chair", "cook_stool"):
        selector = json.loads(original[f"{ASSET_NS}/items/{kind}.json"])["model"]
        recipe_prefix = f"src/kaleidoscope_cookery_plus/data/kaleidoscope/recipe/cookery_plus/{kind}/"
        recipe_files = {path[len(recipe_prefix):-5]: value for path, value in files.items()
                        if path.startswith(recipe_prefix) and path.endswith(".json")}
        if set(recipe_files) != set(WOODS):
            raise ValueError(f"Pinned Deco {kind} wood recipes changed")
        for wood in WOODS:
            raw = json.loads(recipe_files[wood])
            result = raw["result"]
            components = result["components"]
            inherited = components["entity_data"]["data"]
            expected_model = f"{ASSET_NS}:{kind}"
            expected_strings = [wood, "empty"] if kind == "chair" else [wood]
            if (raw["type"].removeprefix("minecraft:") != "stonecutting" or result["id"] != "item_frame"
                    or result["count"] != 1 or inherited["index"] != kind
                    or components["item_model"] != expected_model
                    or components["custom_model_data"]["strings"] != expected_strings
                    or inherited["inheritance"]["item"]["components"]["minecraft:item_model"] != expected_model
                    or inherited["inheritance"]["item"]["components"]["minecraft:custom_model_data"]["strings"] != [wood, "empty"]):
                raise ValueError(f"Pinned Deco {kind}/{wood} recipe changed")
            ingredient = raw["ingredient"]
            expected_ingredient = "#" + wood + ("_blocks" if wood == "bamboo" else
                                                 "_stems" if wood in ("crimson", "warped") else "_logs")
            if ingredient != expected_ingredient:
                raise ValueError(f"Unsupported Deco seat ingredient: {ingredient}")
            identifier = f"kaleidoscope_deco:{kind}/{wood}"
            name_key = components["item_name"]["translate"]
            definition = {"material": "paper", "data": {"item_name": labels.get(name_key, name_key)},
                          "model": model_tree(static_model(selector, [wood, "empty"]), namespaces)}
            placeable(definition, identifier)
            items[identifier] = definition
            furnishing = furniture(identifier, "kaleidoscope_deco:wooden_seat", seated=kind == "chair")
            ground = furnishing["variants"]["ground"]
            ground["hitboxes"][0].update(width=1.0 if kind == "chair" else 0.8,
                                        height=1.0 if kind == "chair" else 0.5)
            # The source's closed invisible shulker provides real collision in addition to its interaction entity.
            ground["hitboxes"].append({"type": "shulker", "position": "0,-0.2,0" if kind == "chair" else "0,-0.18,0",
                                      "scale": 0.8 if kind == "chair" else 0.6, "peek": 0, "direction": "UP",
                                      "interactive": False, "interaction_entity": False, "invisible": True,
                                      "can_use_item_on": False, "can_be_hit_by_projectile": False,
                                      "blocks_building": True})
            if kind == "chair":
                for color in COLORS:
                    visual = identifier + "/cushion/" + color
                    items[visual] = {"material": "paper", "data": {"item_name": labels.get(name_key, name_key)},
                                     "model": model_tree(static_model(selector, [wood, color]), namespaces)}
                    variant = copy.deepcopy(ground)
                    variant["elements"][0]["item"] = visual
                    furnishing["variants"]["cushion/" + color] = variant
            furnishings[identifier] = furnishing
            entries.append({"id": identifier, "category": "kaleidoscope_cookery_plus", "source": SOURCE, "rules": []})
            recipes[identifier] = {"type": "stonecutting", "ingredients": ["#minecraft:" + ingredient[1:]],
                                   "result": {"id": identifier, "count": 1}}
    return recipes
