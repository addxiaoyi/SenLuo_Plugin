"""CE variants for the tavern controllers, derived from the upstream block models."""
from __future__ import annotations

import copy
import json

VANILLA_BOTTLES = {
    "minecraft:potion": "water_bottle",
    "minecraft:honey_bottle": "honey_bottle",
    "minecraft:dragon_breath": "dragon_breath_bottle",
}


def add_variants(items: dict, furnishings: dict, catalog: dict, assets: dict) -> None:
    trellis = furnishings["kaleidoscope_tavern:trellis"]
    for fruit in ("grape", "ice_grape", "gold_grape"):
        plant = fruit.replace("grape", "grapevine_trellis")
        for vine_age in range(4):
            visual_id = f"kaleidoscope_tavern:visual/{fruit}/vine_{vine_age}"
            model = f"kaleidoscope_tavern:block/plant/{plant}/single_stage{vine_age}"
            require_model(model, assets)
            items[visual_id] = visual(visual_id, model)
            variant = copy.deepcopy(trellis["variants"]["ground"])
            variant["elements"][0]["item"] = visual_id
            trellis["variants"][f"{fruit}_{vine_age}_0"] = variant
        for fruit_age in range(6):
            visual_id = f"kaleidoscope_tavern:visual/{fruit}/fruit_{fruit_age}"
            model = f"kaleidoscope_tavern:block/plant/{fruit}_crop/stage{fruit_age}"
            require_model(model, assets)
            items[visual_id] = visual(visual_id, model)
            variant = copy.deepcopy(trellis["variants"][f"{fruit}_3_0"])
            variant["elements"].append({"item": visual_id, "display_transform": "none", "position": "0,-0.5,0"})
            variant["hitboxes"].append({"type": "interaction", "position": "0,-1,0", "height": 1, "width": 0.75})
            trellis["variants"][f"{fruit}_3_{fruit_age + 1}"] = variant
    for carrier in ("empty_bottle", "empty_glassware"):
        bottle = furnishings["kaleidoscope_tavern:" + carrier]
        for output in sorted({*catalog["effects"], *(recipe["output"] for recipe in catalog["barrel"]),
                              "kaleidoscope_tavern:signature_cocktail", "kaleidoscope_tavern:watermelon_juice", *VANILLA_BOTTLES}):
            variant = copy.deepcopy(bottle["variants"]["ground"])
            display = output
            if output in VANILLA_BOTTLES:
                name = VANILLA_BOTTLES[output]
                display = f"kaleidoscope_tavern:visual/{name}"
                model = f"kaleidoscope_tavern:block/brew/{name}"
                require_model(model, assets)
                items[display] = visual(display, model)
            variant["elements"][0]["item"] = display
            bottle["variants"][output.replace(":", "/")] = variant
    add_pressing_levels(items, furnishings["kaleidoscope_tavern:pressing_tub"], catalog["pressing"], assets)
    add_sandwich_boards(furnishings)
    add_molotov_furniture(items, furnishings)


BOARD_STYLES = ("base", "grass", "allium", "azure_bluet", "cornflower", "orchid", "peony", "pink_petals",
                "pitcher_plant", "poppy", "sunflower", "torchflower", "tulip", "wither_rose")


def add_molotov_furniture(items: dict, furnishings: dict) -> None:
    shapes = {"molotov": [(0, 0, .375, .875)],
              "holder": [(0, -.1875, .375, 1), (0, .1875, .375, 1)],
              "tilted_rack": [(-.1875, .125, .625, .875), (.1875, .125, .625, .875)],
              "circular_rack": [(0, 0, 1, .125)], "cellar_cabinet": [(0, 0, 1, 1)]}
    present = {name: furnishings.get("kaleidoscope_tavern:" + name) for name in shapes}
    if not any(present.values()):
        return
    if not all(present.values()):
        raise ValueError("All five Molotov and storage definitions must be placeable together")
    for name, board in present.items():
        identifier = "kaleidoscope_tavern:" + name
        items[identifier]["behavior"]["rules"]["ground"]["rotation"] = "four"
        board["variants"]["ground"]["hitboxes"] = [
            {"type": "interaction", "position": f"{x},0,{z}", "width": width, "height": height}
            for x, z, width, height in shapes[name]]
    # The source bottle naturally uses SPEAR for up to 72000 ticks. Do not inject temporary components
    # into live player stacks: drift or a crash must not leave a permanently modified item behind.
    items["kaleidoscope_tavern:molotov"]["data"]["components"] = {"minecraft:consumable": {
        "consume_seconds": 3600, "animation": "spear", "has_consume_particles": False}}


def add_sandwich_boards(furnishings: dict) -> None:
    present = {style: furnishings.get(f"kaleidoscope_tavern:{style}_sandwich_board") for style in BOARD_STYLES}
    if not any(present.values()):
        return  # Small isolated bottle/pressing fixtures do not include this separate group.
    if not all(present.values()):
        raise ValueError("All 14 sandwich boards must be placeable together")
    for board in present.values():
        ground = board["variants"]["ground"]
        ground["hitboxes"][0].update({"height": 1.375, "width": 0.75})
        for style in BOARD_STYLES:
            variant = copy.deepcopy(ground)
            variant["elements"][0]["item"] = f"kaleidoscope_tavern:{style}_sandwich_board"
            board["variants"][style] = variant


def add_pressing_levels(items: dict, tub: dict, recipes: list[dict], assets: dict) -> None:
    for fluid in sorted({recipe["fluid"] for recipe in recipes}):
        namespace, name = fluid.split(":", 1)
        texture = f"block/{name}_still"
        if f"{namespace}/textures/{texture}.png" not in assets:
            raise ValueError(f"Missing pressing tub texture: {fluid}")
        for level in range(1, 9):
            model = f"block/brew/pressing_liquid/{name}_{level}"
            height = 2 + level / 2
            assets[f"{namespace}/models/{model}.json"] = json.dumps({
                "ambientocclusion": False,
                "textures": {"liquid": f"{namespace}:{texture}"},
                "elements": [{"from": [2, height, 2], "to": [14, height, 14], "shade": False,
                              "faces": {"up": {"texture": "#liquid", "uv": [0, 0, 16, 16]}}}],
            }).encode("utf-8")
            visual_id = f"kaleidoscope_tavern:visual/pressing_liquid/{name}_{level}"
            items[visual_id] = visual(visual_id, f"{namespace}:{model}")
            variant = copy.deepcopy(tub["variants"]["ground"])
            variant["elements"].append({"item": visual_id, "display_transform": "none", "position": "0,0.5,0"})
            tub["variants"][f"{namespace}/{name}/{level}"] = variant


def visual(id_: str, model: str) -> dict:
    return {"material": "paper", "data": {"item_name": id_}, "model": {"type": "minecraft:model", "path": model}}


def require_model(model: str, assets: dict) -> None:
    namespace, path = model.split(":", 1)
    if f"{namespace}/models/{path}.json" not in assets:
        raise ValueError(f"Missing tavern state model: {model}")
