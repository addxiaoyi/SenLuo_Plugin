"""Run the native CE gameplay/configuration smoke on a disposable loopback instance.

This does not simulate players, assert restart persistence, or accept client visuals.
The probe must be enabled explicitly in CookeryAcceptanceProbe/config.yml.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
from zipfile import ZipFile, BadZipFile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("senluo_connected_runner", Path(__file__).with_name("connected") / "run.py")
CONNECTED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONNECTED)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


@contextmanager
def instance_lock(instance: Path):
    """An OS-released lock also survives an interrupted runner without a stale approval step."""
    with (instance / ".series-gameplay-run.lock").open("a+b") as handle:
        if handle.seek(0, 2) == 0:
            handle.write(b"\0")
            handle.flush()
        handle.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == "nt":
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle, fcntl.LOCK_UN)


def run(args) -> int:
    instance, output = args.instance.resolve(), args.output.resolve()
    for path in (instance, output):
        if not path.is_relative_to(ROOT / "build/acceptance"):
            raise ValueError("Expected a disposable path under build/acceptance")
    properties = dict(line.split("=", 1) for line in (instance / "server.properties").read_text("utf8").splitlines()
                      if "=" in line and not line.startswith("#"))
    if any(properties.get(key) != value for key, value in {
        "server-ip": "127.0.0.1", "level-name": "acceptance-world", "enable-rcon": "false"
    }.items()):
        raise ValueError("Expected an isolated loopback acceptance-world with RCON disabled")
    if "eula=true" not in (instance / "eula.txt").read_text("utf8").splitlines():
        raise ValueError("The operator must already have accepted the Minecraft EULA")
    output.mkdir(parents=True, exist_ok=False)
    log = output / "console.log"
    report_dir = instance / "plugins/CookeryAcceptanceProbe"
    probe = report_dir / "series-gameplay-smoke.json"
    pack = instance / "plugins/CraftEngine/cache/hosted/default/resource_pack.zip"
    started = time.time()
    run_id = str(uuid.uuid4())
    result = {"scope": "native CE definitions/placement/in-process NBT smoke; no player, restart or visual acceptance",
              "instance": str(instance), "runId": run_id, "serverExit": None, "forcedServerStop": False, "errors": [],
              "probe": None, "packGenerated": False, "passed": False,
              "productionJars": {path.name: sha(path) for path in sorted((instance / "plugins").glob("Kaleidoscope*.jar"))},
              "probeJarSha256": sha(instance / "plugins/CookeryAcceptanceProbe.jar"),
              "serverJarSha256": sha(instance / "server.jar")}
    server = None
    generated = False
    workflow_completed = False
    with log.open("wb") as stdout, log.open("rb") as tail:
        try:
            server = subprocess.Popen([args.java, "-Xms512M", "-Xmx2200M", "-Dfile.encoding=UTF-8",
                                       "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                                       "-Dterminal.jline=false", "-Dterminal.ansi=false",
                                       f"-Dsenluo.series.smoke.run_id={run_id}", "-jar", "server.jar", "nogui"],
                                      cwd=instance, stdin=subprocess.PIPE, stdout=stdout, stderr=subprocess.STDOUT,
                                      creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
                                      start_new_session=os.name != "nt")
            deadline = time.monotonic() + 300
            while server.poll() is None and time.monotonic() < deadline:
                while True:
                    at = tail.tell()
                    raw = tail.readline()
                    if not raw or not raw.endswith(b"\n"):
                        tail.seek(at)
                        break
                    line = raw.decode("utf8", "replace")
                    if re.search(r"资源包工作流 default 执行完成|Resource pack workflow default (?:completed|finished)", line, re.I):
                        workflow_completed = True
                    if "Done (" in line and not generated:
                        server.stdin.write(b"ce workflow default\n")
                        server.stdin.flush()
                        generated = True
                if probe.exists() and probe.stat().st_mtime >= started:
                    try:
                        candidate = json.loads(probe.read_text("utf8"))
                        if not isinstance(candidate, dict) or not isinstance(candidate.get("passed"), bool):
                            result["errors"].append("Malformed current native probe report")
                            break
                        if candidate.get("runId") == run_id:
                            result["probe"] = candidate
                    except (ValueError, OSError):
                        pass
                if workflow_completed and pack.exists() and pack.stat().st_mtime >= started and not result["packGenerated"]:
                    try:
                        with ZipFile(pack) as archive:
                            result["packGenerated"] = bool(archive.namelist()) and archive.testzip() is None
                    except (BadZipFile, OSError):
                        pass
                if result["probe"] is not None and (result["probe"].get("passed") is False or result["packGenerated"]):
                    break
                time.sleep(.1)
            else:
                result["errors"].append("Server exited early or native smoke/resource workflow timed out")
        except (OSError, ValueError, subprocess.SubprocessError) as failure:
            result["errors"].append(f"Runner: {type(failure).__name__}: {failure}")
        finally:
            if server is not None and server.poll() is None:
                try:
                    server.stdin.write(b"stop\n")
                    server.stdin.flush()
                    server.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    result["forcedServerStop"] = True
                    CONNECTED.terminate_tree(server)
                except (OSError, subprocess.SubprocessError) as failure:
                    result["errors"].append(f"Shutdown: {failure}")
                    CONNECTED.terminate_tree(server)
            if server is not None:
                result["serverExit"] = server.poll()
                if server.stdin is not None:
                    server.stdin.close()
    result["errors"].extend(line for line in log.read_text("utf8", errors="replace").splitlines() if CONNECTED.LOG_ERROR.search(line))
    for name in ("food-smoke.json", "enchantment-smoke.json", "series-gameplay-smoke.json"):
        source = report_dir / name
        if source.exists() and source.stat().st_mtime >= started:
            (output / name).write_bytes(source.read_bytes())
    result["logSha256"] = sha(log)
    if result["packGenerated"]:
        result["packSha256"] = sha(pack)
    result["elapsedSeconds"] = round(time.time() - started, 3)
    result["passed"] = (result["serverExit"] == 0 and not result["forcedServerStop"] and not result["errors"]
                        and result["packGenerated"] and isinstance(result["probe"], dict) and result["probe"].get("passed") is True)
    (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", "utf8")
    print(json.dumps({"passed": result["passed"], "elapsedSeconds": result["elapsedSeconds"], "output": str(output),
                      "errors": result["errors"][:8]}, ensure_ascii=False), flush=True)
    return 0 if result["passed"] else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("instance", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--java", required=True)
    args = parser.parse_args()
    instance = args.instance.resolve()
    if not instance.is_relative_to(ROOT / "build/acceptance") or not instance.is_dir():
        raise ValueError("Expected an existing disposable instance under build/acceptance")
    with instance_lock(instance):
        return run(args)


if __name__ == "__main__":
    sys.exit(main())
