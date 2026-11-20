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
