"""Translate the pinned datapack's declarative interactions, without executing commands."""
from __future__ import annotations

import copy
import json
import re
from typing import Any

TULIP_BOARD = "kaleidoscope_bar:blackboard/9"
PLAIN_BOARD = "kaleidoscope_bar:blackboard/0"
TULIP_LOOT = "kaleidoscope:bar/events/tulip_loot"
TULIP_LOOT_FILE = "src/kaleidoscope_bar/data/kaleidoscope/function/bar/events/tulip_loot/execute.mcfunction"


def deco_id(index: str) -> str:
    return "kaleidoscope_deco:" + index.replace(":", "/")


def flatten(event: dict) -> list[dict]:
    if event.get("event") == "group":
        return [leaf for child in event.get("args", {}).get("events", []) for leaf in flatten(child)]
    return [event]


def function_sounds(files: dict[str, bytes], name: str) -> list[str]:
    if not name:
        return []
    namespace, path = name.split(":", 1)
    suffix = f"/data/{namespace}/function/{path}.mcfunction"
    matches = [content for file, content in files.items() if re.match(r"src/[^/]+/data/", file) and file.endswith(suffix)]
    if len(matches) != 1:
        raise ValueError(f"Unresolved function: {name}")
    sounds = []
    for line in matches[0].decode("utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("playsound "):
            sound = line.split()[1]
            sounds.append(sound if ":" in sound else "minecraft:" + sound)
        elif not line.startswith("particle "):
            raise ValueError(f"Function has additional behavior: {name}")
    return sounds


def tulip_drops(files: dict[str, bytes]) -> list[str]:
    source_bytes = files.get(TULIP_LOOT_FILE)
    if source_bytes is None:
        raise ValueError("Missing pinned tulip board loot function")
    source = source_bytes.decode("utf-8").strip()
    if not source:
        raise ValueError("Empty pinned tulip board loot function")
    match = re.fullmatch(r'loot spawn ~ ~ ~ loot \{"pools":\[\{rolls:1,entries:\[(.+)\]\}\]\}', source)
    if not match:
        raise ValueError("Unrecognized tulip board loot command")
    entries = re.fullmatch(r'(?:\{type:"item",name:"(?:red|orange|white|pink)_tulip"\},?)+', match[1])
    drops = re.findall(r'name:"([a-z_]+)"', match[1])
    if not entries or len(drops) != 4 or len(set(drops)) != 4:
        raise ValueError("Unrecognized tulip board loot pool")
    return ["minecraft:" + drop for drop in drops]


def item_tag_sources(files: dict[str, bytes]) -> dict[str, dict[str, dict]]:
    tags = {}
    for path, content in files.items():
        match = re.fullmatch(r"src/([^/]+)/data/([^/]+)/tags/item/(.+)\.json", path)
        if match:
            module, namespace, name = match.groups()
            tags.setdefault(f"{namespace}:{name}", {})[module] = json.loads(content)
    return tags


def tag_inputs(name: str, module: str, tags: dict, trail: tuple = ()) -> list[str]:
    name = name if ":" in name else "minecraft:" + name
    if name.startswith("minecraft:"):
        return ["#" + name]
    if name in trail:
        raise ValueError(f"Cyclic interaction item tag: {name}")
    sources = tags.get(name, {})
    definition = sources.get(module)
    if definition is None and sources:
        first = next(iter(sources.values()))
        if all(other == first for other in sources.values()):
            definition = first
    if not definition or set(definition) - {"values", "replace"} or not isinstance(definition.get("values"), list):
        raise ValueError(f"Missing or ambiguous interaction item tag: {name}")
    result = []
    for member in definition["values"]:
        if isinstance(member, dict) and set(member) == {"id", "required"} and member["required"] is False:
            member = member["id"]
        if not isinstance(member, str) or not re.fullmatch(r"#?(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+", member):
            raise ValueError(f"Unsupported member in interaction item tag: {name}")
        if member.startswith("#"):
            result.extend(tag_inputs(member[1:], module, tags, (*trail, name)))
        else:
            item = member if ":" in member else "minecraft:" + member
            if not item.startswith("minecraft:"):
                raise ValueError(f"Unmapped custom item in interaction item tag: {name}")
            result.append(item)
    if not result:
        raise ValueError(f"Empty interaction item tag: {name}")
    return sorted(set(result))


def rules(definition: dict, files: dict[str, bytes], pending: list[str], index: str,
          module: str = "", tags: dict | None = None) -> list[dict]:
    click = definition.get("events", {}).get("right_click", {})
    converted = []
    for event in [*click.get("criteria", []), click.get("fallback", {})]:
        if not event or event.get("event") in ("sit", "__nothing__"):
            continue
        rule: dict[str, Any] = {"inputs": [], "wand": False, "sneak": -1, "target": "", "sounds": [],
                                "pending": False}
        predicate = event.get("predicate", "")
        if predicate:
            if predicate not in ("kaleidoscope:shift", "!kaleidoscope:shift"):
                pending.append(f"Interaction predicate pending: {predicate}")
                continue
            rule["sneak"] = 0 if predicate.startswith("!") else 1
        condition = event.get("item", {})
        components = condition.get("components", {})
        custom_tag = False
        if components:
            if components == {"minecraft:custom_data": {"kaleidoscope": "trans"}}:
                rule["wand"] = True
            else:
                pending.append(f"Interaction item components pending: {components}")
                continue
        elif "id" in condition:
            item = condition["id"]
            if item == "*":
                rule["inputs"] = ["*"]
            elif item.startswith("#"):
                custom_tag = ":" in item and not item.startswith("#minecraft:")
                try:
                    rule["inputs"] = tag_inputs(item[1:], module, tags or {})
                except ValueError as error:
                    pending.append(f"Interaction item tag pending: {error}")
                    continue
            else:
                rule["inputs"] = [item if ":" in item else "minecraft:" + item]
        try:
            for action in flatten(event):
                kind, args = action.get("event"), action.get("args", {})
                if kind == "trans":
                    if rule["target"]:
                        raise ValueError("Multiple transitions in one interaction")
                    rule["target"] = deco_id(args["index"])
                    function = args.get("func", "")
                    if function in ("kaleidoscope:bar/events/counter_stool/switch_to_short/trans",
                                    "kaleidoscope:bar/events/counter_stool/switch_to_tall/trans"):
                        rule["sounds"].append("minecraft:entity.copper_golem.step")
                    else:
                        rule["sounds"].extend(function_sounds(files, function))
                elif kind == "sound":
                    sound = args["sound"]
                    rule["sounds"].append(sound if ":" in sound else "minecraft:" + sound)
                elif kind == "custom" and args.get("func") in ("kaleidoscope:events/wand", "kaleidoscope:doll/events/note"):
                    continue
                elif kind == "custom" and index.startswith("kaleidoscope_bar:blackboard/") and args.get("func") == "kaleidoscope:bar/events/custom_loot":
                    item = args.get("item", "")
                    if not re.fullmatch(r"(?:minecraft:)?[a-z0-9_]+", item) or rule.get("drops"):
                        raise ValueError("Unsupported custom loot item")
                    rule["drops"] = [item if ":" in item else "minecraft:" + item]
                elif kind == "custom" and index == TULIP_BOARD and args.get("func") == TULIP_LOOT:
                    if rule.get("drops"):
                        raise ValueError("Multiple loot actions")
                    rule["drops"] = tulip_drops(files)
                else:
                    raise ValueError(f"Event pending: {kind}: {args.get('func', '')}")
            if rule.get("drops") and not rule["target"]:
                raise ValueError("Loot action requires a state transition to prevent repeated extraction")
            if index == PLAIN_BOARD and rule["target"] == deco_id(TULIP_BOARD):
                if len(flatten(event)) != 1 or sorted(rule["inputs"]) != sorted(tulip_drops(files)):
                    raise ValueError("Unrecognized tulip board placement transition")
                rule["consume"] = True
            if rule["target"] or rule["sounds"]:
                converted.append(rule)
        except ValueError as error:
            if not custom_tag:
                rule["target"] = ""
                rule["sounds"] = []
                rule["pending"] = True
                rule.pop("consume", None)
                rule.pop("drops", None)
                converted.append(rule)
            pending.append(f"Interaction action pending: {error}")
    return converted


def selected_model(model: dict, strings: list[str], flags: list[bool], floats: list[float] | None = None,
                   *, context: str | None = None) -> dict:
    """Resolve pinned component state at every depth, preserving other live selectors."""
    import math
    import struct

    def number(value):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            raise ValueError("Invalid custom model data number")
        try:
            result = struct.unpack('f', struct.pack('f', value))[0]
        except (OverflowError, struct.error) as error:
            raise ValueError("Custom model data number exceeds float range") from error
        if not math.isfinite(result):
            raise ValueError("Custom model data number exceeds float range")
        return result

    def visit(node, depth=0):
        if depth > 64:
            raise ValueError("Item model nesting exceeds 64 levels")
        kind = node['type'].removeprefix('minecraft:')
        prop = node.get('property', '').removeprefix('minecraft:')
        if prop == 'custom_model_data':
            slot = node.get('index', 0)
            if type(slot) is not int or slot < 0:
                raise ValueError("Invalid custom model data index")
            if kind == 'condition':
                value = flags[slot] if slot < len(flags) else False
                if type(value) is not bool:
                    raise ValueError("Invalid custom model data flag")
                return visit(node['on_true'] if value else node['on_false'], depth + 1)
            if kind == 'range_dispatch':
                values = floats or []
                value = number(number(values[slot] if slot < len(values) else 0) * number(node.get('scale', 1)))
                entries = sorted((number(entry['threshold']), i, entry['model']) for i, entry in enumerate(node['entries']))
                if len({threshold for threshold, _, _ in entries}) != len(entries):
                    raise ValueError("Duplicate custom model data threshold")
                child = node.get('fallback', {'type': 'minecraft:empty'})
                for threshold, _, candidate in entries:
                    if threshold <= value:
                        child = candidate
                return visit(child, depth + 1)
        if kind == 'select' and (prop == 'custom_model_data' or prop == 'display_context' and context is not None):
            value = (strings[slot] if slot < len(strings) else '') if prop == 'custom_model_data' else context
            child = node.get('fallback', {'type': 'minecraft:empty'})
            for case in node['cases']:
                if value in (case['when'] if isinstance(case['when'], list) else [case['when']]):
                    child = case['model']
                    break
            return visit(child, depth + 1)
        result = copy.deepcopy(node)
        if kind == 'composite':
            result['models'] = [visit(child, depth + 1) for child in node['models']]
        elif kind in ('select', 'range_dispatch'):
            field = 'cases' if kind == 'select' else 'entries'
            result[field] = [{**entry, 'model': visit(entry['model'], depth + 1)} for entry in node[field]]
            if 'fallback' in node:
                result['fallback'] = visit(node['fallback'], depth + 1)
        elif kind == 'condition':
            for field in ('on_true', 'on_false'):
                result[field] = visit(node[field], depth + 1)
        return result

    return visit(model)


def inventory_legacy_model(model: dict) -> dict | None:
    """Only emit an exact shared model for inventory, hands and dropped items."""
    contexts = ('gui', 'ground', 'firstperson_lefthand', 'firstperson_righthand',
                'thirdperson_lefthand', 'thirdperson_righthand')
    nodes = [selected_model(model, [], [], context=context) for context in contexts]
    first = nodes[0]
    if (first.get('type') in ('minecraft:model', 'model') and set(first) == {'type', 'path'}
            and all(node == first for node in nodes)):
        return {'path': first['path']}
    return None


def link_variants(entries: list[dict], furnishings: dict, pending: list[str]) -> None:
    by_id = {entry["id"]: entry for entry in entries}
    originals = {id_: copy.deepcopy(furniture["variants"]["ground"]) for id_, furniture in furnishings.items()}
    for entry in entries:
        for rule in entry["rules"]:
            if rule["target"] and rule["target"] not in by_id:
                pending.append(f"Transition target not exported: {entry['id']} -> {rule['target']}")
                rule.update(target="", pending=True)
                rule.pop("consume", None)
                rule.pop("drops", None)
    for origin in entries:
        seen, queue = set(), [origin["id"]]
        while queue:
            id_ = queue.pop()
            if id_ in seen:
                continue
            seen.add(id_)
            furnishings[origin["id"]]["variants"][id_.replace(":", "/")] = copy.deepcopy(originals[id_])
            queue.extend(rule["target"] for rule in by_id[id_]["rules"] if rule["target"])
