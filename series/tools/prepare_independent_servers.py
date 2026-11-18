"""Prepare isolated Paper/Folia directories for the four series plugins.

The command only copies verified artifacts and writes an install manifest.  It
never launches a server, accepts an EULA implicitly, or overwrites an existing
instance.  Runtime and vanilla-client results remain separate evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from zipfile import BadZipFile, ZipFile

from check_independence import check as check_independence

SUITE = Path(__file__).resolve().parents[1]
WORKSPACE = SUITE.parent
MODULES = ("doll", "tavern", "deco", "music")
PLATFORMS = {"paper": 25591, "folia": 25592}


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def archive_member(name: str) -> PurePosixPath:
    member = PurePosixPath(name)
    parts = name.rstrip("/").split("/")
    if (not name or member.is_absolute() or "\\" in name or ":" in name
            or any(part in ("", ".", "..") for part in parts)):
        raise ValueError(f"unsafe archive path: {name!r}")
    return member


def verify_zip(path: Path, required: tuple[str, ...]) -> None:
    try:
        with ZipFile(path) as archive:
            # ZipInfo.filename replaces Windows backslashes before validation.
            entries = [entry.orig_filename for entry in archive.infolist()]
            names = set(entries)
            missing = [entry for entry in required if entry not in names]
            if missing:
                raise ValueError(f"{path.name}: missing {missing}")
            if len(names) != len(entries):
                raise ValueError(f"{path.name}: duplicate archive entries")
            for name in names:
                archive_member(name)
            bad = archive.testzip()
            if bad:
                raise ValueError(f"{path.name}: CRC failed for {bad}")
    except BadZipFile as error:
        raise ValueError(f"{path.name}: invalid ZIP: {error}") from error


def single_match(roots: tuple[Path, ...], patterns: tuple[str, ...], description: str) -> Path:
    matches: dict[Path, None] = {}
    for root in roots:
        if root.is_dir():
            for pattern in patterns:
                for candidate in root.glob(pattern):
                    if candidate.is_file():
                        matches[candidate.resolve()] = None
    if not matches:
        raise ValueError(f"missing {description} matching {', '.join(patterns)}")
    if len(matches) > 1:
        raise ValueError(f"ambiguous {description}: {sorted(str(path) for path in matches)}")
    return next(iter(matches))


def required_artifacts(artifacts: Path, packs: Path, cookery_jar: Path | None,
                       cookery_pack: Path | None, craft_engine: Path | None) -> list[tuple[str, Path, str]]:
    found: list[tuple[str, Path, str]] = []
    for module in MODULES:
        name = f"Kaleidoscope{module.capitalize()}"
        jar = single_match((artifacts / module / "build/libs", artifacts),
                           (f"{name}-*.jar", f"{name}Plugin-*.jar"), f"{module} JAR")
        pack = single_match((packs,), (f"{module}-ce-pack-*.zip",), f"{module} CE pack")
        verify_zip(jar, ("plugin.yml",))
        verify_zip(pack, ("pack.yml", "configuration/content.yml"))
        found.extend(((f"plugins/{jar.name}", jar, "plugin"),
                      (f"plugins/CraftEngine/resources/{module}", pack, "ce-pack")))
    shared_matches = sorted(packs.glob("shared-ce-pack-*.zip"))
    if len(shared_matches) > 1:
        raise ValueError(f"ambiguous shared CE packs: {[path.name for path in shared_matches]}")
    if shared_matches:
        shared = shared_matches[0]
        verify_zip(shared, ("pack.yml",))
        found.append(("plugins/CraftEngine/resources/shared", shared, "shared-pack"))
    if cookery_jar:
        if not cookery_jar.is_file():
            raise ValueError(f"missing Cookery JAR: {cookery_jar}")
        verify_zip(cookery_jar, ("plugin.yml",))
        found.append((f"plugins/{cookery_jar.name}", cookery_jar, "plugin"))
    if cookery_pack:
        if not cookery_pack.is_file():
            raise ValueError(f"missing Cookery CE pack: {cookery_pack}")
        verify_zip(cookery_pack, ("cookery_kitchen/pack.yml", "cookery_kitchen/configuration/kitchen.yml"))
        found.append(("plugins/CraftEngine/resources/cookery_kitchen", cookery_pack, "cookery-pack"))
    if craft_engine:
        if not craft_engine.is_file():
            raise ValueError(f"missing CraftEngine runtime JAR: {craft_engine}")
        try:
            verify_zip(craft_engine, ("plugin.yml",))
        except ValueError:
            verify_zip(craft_engine, ("paper-plugin.yml",))
        found.append((f"plugins/{craft_engine.name}", craft_engine, "runtime"))
    return found


def _within(path: Path, parent: Path) -> bool:
    try:
        path.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def install_artifact(root: Path, relative: str, source: Path, kind: str) -> Path:
    archive_member(relative)
    destination = root / "artifacts" / relative
    if not _within(destination, root / "artifacts"):
        raise ValueError(f"artifact destination escapes acceptance root: {relative}")
    if kind not in ("ce-pack", "shared-pack", "cookery-pack"):
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        return destination
    prefix = "cookery_kitchen/" if kind == "cookery-pack" else ""
    config = "configuration/kitchen.yml" if kind == "cookery-pack" else "configuration/content.yml"
    required = (prefix + "pack.yml",) if kind == "shared-pack" else (prefix + "pack.yml", prefix + config)
    verify_zip(source, required)
    destination.mkdir(parents=True, exist_ok=False)
    with ZipFile(source) as archive:
        for name in archive.namelist():
            if not name.startswith(prefix) or name.endswith("/"):
                continue
            member = archive_member(name[len(prefix):])
            target = destination / Path(*member.parts)
            if not _within(target, destination):
                raise ValueError(f"pack member escapes resource root: {name}")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
    return destination


def prepare(root: Path, artifacts: Path, packs: Path, *, cookery_jar: Path | None = None,
            cookery_pack: Path | None = None, accept_eula: bool = False,
            paper_jar: Path | None = None, folia_jar: Path | None = None,
            craft_engine: Path | None = None, base_resources: Path | None = None,
            server_blocks: int | None = None, early_pack_load: bool = False) -> dict:
    root = root.resolve()
    allowed = WORKSPACE / "build" / "acceptance"
    # Keep test worlds out of the source tree and production locations.
    if not _within(root, allowed):
        raise ValueError(f"isolated server root must be under {allowed}")
    if root.exists() and any(root.iterdir()):
        raise ValueError(f"refusing to overwrite existing acceptance root: {root}")
    display_root = root
    # ZIP members can exceed MAX_PATH even when the isolated server root is short.
    # Apply the extended Windows path only after checking the real destination.
    if sys.platform == "win32":
        raw = str(root)
        prefix = "\\\\?\\"
        root = Path(prefix + ("UNC\\" + raw[2:] if raw.startswith("\\\\") else raw))
    artifacts = artifacts.resolve()
    packs = packs.resolve()
    independent = check_independence(source_root=SUITE, artifacts=artifacts, packs=packs)
    if independent["errors"]:
        raise ValueError("independence check failed: " + "; ".join(independent["errors"]))
    files = required_artifacts(artifacts, packs, cookery_jar, cookery_pack, craft_engine)
    if base_resources and not base_resources.is_dir():
        raise ValueError(f"missing CraftEngine base resources directory: {base_resources}")
    resource_prefix = "plugins/CraftEngine/resources/"
    managed_roots = {relative[len(resource_prefix):].split("/", 1)[0].casefold()
                     for relative, _, _ in files if relative.startswith(resource_prefix)}
    base_entries = tuple(sorted(base_resources.iterdir())) if base_resources else ()
    for source in base_entries:
        if source.name.casefold() != "cookery_kitchen" and source.name.casefold() in managed_roots:
            raise ValueError(f"base resources would overwrite managed pack {source.name}: {source}")
    engine_config = None
    if server_blocks is not None or early_pack_load:
        if craft_engine is None:
            raise ValueError("a CraftEngine JAR is required to configure pack loading")
        with ZipFile(craft_engine) as archive:
            if "config.yml" not in archive.namelist():
                raise ValueError(f"CraftEngine runtime has no default config.yml: {craft_engine}")
            engine_config = archive.read("config.yml").decode("utf-8")
        overrides = {}
        if server_blocks is not None:
            if type(server_blocks) is not int or not 1 <= server_blocks <= 100000:
                raise ValueError("server-blocks must be an integer between 1 and 100000")
            overrides["serverside-blocks"] = (r"\d+", str(server_blocks))
        if early_pack_load:
            # On 26.9.1 the deferred loader can block Folia's global tick, and the
            # asynchronous asset cache can race the first explicit pack workflow.
            overrides["delay-configuration-load"] = ("true|false", "false")
            overrides["cache-resource-files"] = ("true|false", "false")
        for key, (old_value, new_value) in overrides.items():
            engine_config, matches = re.subn(rf"(?m)^(  {key}: )(?:{old_value})([ \t]*\r?)$",
                                             lambda match: match[1] + new_value + match[2], engine_config)
            if matches != 1:
                raise ValueError(f"CraftEngine config.yml must define exactly one {key} value")
    runtimes = {"paper": paper_jar, "folia": folia_jar}
    report = {
        "created_at": datetime.now(timezone.utc).isoformat(),
        "scope": "isolated installation preparation; server and vanilla-client acceptance not run",
        "minecraft": "1.21.11",
        "java": 21,
        "craftEngine": "26.9.1",
        "independence": independent,
        "artifacts": {},
        "base_resources": [],
        "instances": {},
    }
    root.mkdir(parents=True, exist_ok=False)
    if engine_config is not None:
        config_path = "plugins/CraftEngine/config.yml"
        config_bytes = engine_config.encode("utf-8")
        target = root / "artifacts" / config_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(config_bytes)
        report["craftEngineConfig"] = {"path": config_path, "serverBlocks": server_blocks, "earlyPackLoad": early_pack_load,
                                      "sha256": hashlib.sha256(config_bytes).hexdigest()}
    for relative, source, kind in files:
        destination = install_artifact(root, relative, source, kind)
        report["artifacts"][relative] = {"kind": kind, "source": str(source),
                                          "sha256": digest(source), "bytes": source.stat().st_size}
    if base_resources:
        target_root = root / "artifacts/plugins/CraftEngine/resources"
        for source in base_entries:
            if source.name.casefold() == "cookery_kitchen":
                continue
            target = target_root / source.name
            if source.is_dir():
                shutil.copytree(source, target, dirs_exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
            report["base_resources"].append({"name": source.name, "source": str(source)})
    for platform, port in PLATFORMS.items():
        instance = root / platform
        plugins = instance / "plugins"
        plugins.mkdir(parents=True, exist_ok=True)
        if engine_config is not None:
            target = instance / report["craftEngineConfig"]["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(engine_config.encode("utf-8"))
        runtime = runtimes[platform]
        if runtime:
            if not runtime.is_file():
                raise ValueError(f"missing {platform} server JAR: {runtime}")
            shutil.copyfile(runtime, instance / "server.jar")
        (instance / "server.properties").write_text(
            f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=true\n"
            "enable-rcon=false\nenable-query=false\nmax-players=8\nview-distance=4\n"
            "simulation-distance=4\nspawn-protection=0\nlevel-name=acceptance-world\n",
            encoding="utf-8")
        eula_comment = ("# Accepted explicitly by the server operator.\n"
                        if accept_eula else
                        "# Set true only after the server operator accepts the Minecraft EULA.\n")
        (instance / "eula.txt").write_text(eula_comment +
                                           f"eula={'true' if accept_eula else 'false'}\n",
                                           encoding="utf-8")
        for relative, _, kind in files:
            if relative.startswith("plugins/"):
                source = root / "artifacts" / relative
                target = instance / relative
                if source.is_dir():
                    shutil.copytree(source, target, dirs_exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source, target)
        if base_resources:
            source_root = root / "artifacts/plugins/CraftEngine/resources"
            target_root = instance / "plugins/CraftEngine/resources"
            for entry in report["base_resources"]:
                source = source_root / entry["name"]
                target = target_root / entry["name"]
                if source.is_dir():
                    shutil.copytree(source, target, dirs_exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source, target)
        (instance / "start.sh").write_text(
            "#!/usr/bin/env sh\nset -eu\ncd \"$(dirname \"$0\")\"\n"
            "exec java -Xms512M -Xmx2G -Dfile.encoding=UTF-8 -jar server.jar nogui\n",
            encoding="utf-8")
        report["instances"][platform] = {
            "directory": str(display_root / platform), "port": port,
            "serverJar": str(runtime) if runtime else None,
            "eulaAccepted": accept_eula,
            "startup": "NOT_RUN", "resourcePack": "NOT_RUN", "vanillaClient": "NOT_RUN",
            "plugins": {path: value["sha256"] for path, value in report["artifacts"].items()
                        if path.startswith("plugins/") and "/resources/" not in path},
            "cePacks": {path: value["sha256"] for path, value in report["artifacts"].items()
                        if "/resources/" in path},
        }
    (root / "INSTALL-MANIFEST.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                                                 encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=WORKSPACE / "build/acceptance/series-independent")
    parser.add_argument("--artifacts", type=Path, default=SUITE)
    parser.add_argument("--packs", type=Path, required=True)
    parser.add_argument("--cookery-jar", type=Path)
    parser.add_argument("--cookery-pack", type=Path)
    parser.add_argument("--paper-jar", type=Path)
    parser.add_argument("--folia-jar", type=Path)
    parser.add_argument("--craft-engine", type=Path, help="CraftEngine runtime plugin JAR")
    parser.add_argument("--server-blocks", type=int,
                        help="Override block.serverside-blocks in a copy of the runtime's default config")
    parser.add_argument("--early-pack-load", action="store_true",
                        help="Load packs during CE startup and bypass its racy in-memory resource cache")
    parser.add_argument("--base-resources", type=Path,
                        help="Prepared CraftEngine resources directory containing default packs")
    parser.add_argument("--accept-eula", action="store_true",
                        help="Only when the server operator explicitly accepted the Minecraft EULA")
    args = parser.parse_args()
    try:
        report = prepare(args.root, args.artifacts, args.packs, cookery_jar=args.cookery_jar,
                         cookery_pack=args.cookery_pack, accept_eula=args.accept_eula,
                         paper_jar=args.paper_jar, folia_jar=args.folia_jar,
                         craft_engine=args.craft_engine, base_resources=args.base_resources,
                         server_blocks=args.server_blocks, early_pack_load=args.early_pack_load)
    except (OSError, ValueError, BadZipFile) as error:
        print(f"Independent server preparation failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
