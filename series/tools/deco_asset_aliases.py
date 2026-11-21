"""Exact aliases for known moved assets in the pinned Deco source."""
from __future__ import annotations


ALIASES = {
    'kaleidoscope_cookery/textures/block/plate.png': 'kaleidoscope_cookery/textures/block/meals/plate.png',
    'kaleidoscope_cookery/textures/block/porcelain_plate.png': 'kaleidoscope_cookery/textures/block/meals/porcelain_plate.png',
    'kaleidoscope_cookery/models/item/slime_ball_meal.json': 'kaleidoscope_cookery/models/meals/item/slime_ball_meal.json',
    'kaleidoscope_cookery/models/item/mysterious.json': 'kaleidoscope_cookery/models/meals/item/mysterious.json',
}


def add_moved_asset_aliases(assets: dict[str, bytes]) -> list[dict[str, str]]:
    added = []
    for old, current in ALIASES.items():
        if old in assets or current not in assets:
            continue
        assets[old] = assets[current]
        added.append({'alias': old, 'source': current})
    return added


def add_missing_meal_gui_aliases(assets: dict[str, bytes]) -> list[dict[str, str]]:
    """Use each meal's source-authored first state for legacy GUI model paths absent upstream."""
    added = []
    prefix = "kaleidoscope_cookery/items/meals/gui/"
    for path in sorted(tuple(assets)):
        if not path.startswith(prefix) or not path.endswith(".json"):
            continue
        name = path[len(prefix):-5]
        alias = f"kaleidoscope_cookery/models/meals/gui/{name}.json"
        source = f"kaleidoscope_cookery/models/meals/block/{name}/0.json"
        if alias in assets or source not in assets:
            continue
        assets[alias] = assets[source]
        added.append({"alias": alias, "source": source})
    return added
