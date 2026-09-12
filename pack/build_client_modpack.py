#!/usr/bin/env python3
"""Build the BREAKFRONT client modpack for the PC launcher.

Pulls the client ``mods/`` set that the breakfront repo already assembles
(``breakfront-dev-client-mods-<sha>.zip``, served publicly by the bfupdate
mirror) and wraps it into a Modrinth ``.mrpack`` (``modpack.zip``) with a
preset server, so HMCL's bundled-modpack auto-install
(``.hmcl/modpack/modpack.zip``) can create a ready-to-play BREAKFRONT instance.

Stdlib only. Output: ``<out>/modpack.zip``.

Usage:
    python3 pack/build_client_modpack.py --out out
"""
from __future__ import annotations

import argparse
import io
import json
import os
import struct
import urllib.request
import zipfile
from pathlib import Path

UPDATE_BASE = os.environ.get("BF_UPDATE_BASE", "https://bfupdate.geekhonize.top")
MC_VERSION = "1.21.1"
FABRIC_LOADER = "0.19.5"
SERVER_IP = "play.geekhonize.top:25565"
SERVER_NAME = "BREAKFRONT 破阵前线"
PACK_NAME = "BREAKFRONT 破阵前线"
UA = {"User-Agent": "breakfront-launcher-builder/1.0"}


def http_bytes(url: str) -> bytes:
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=180) as resp:
        return resp.read()


# ---------------------------------------------------------------- NBT (servers.dat)
# Minimal big-endian NBT writer — just enough for a servers.dat entry.

_TAG_END, _TAG_BYTE, _TAG_STRING, _TAG_LIST, _TAG_COMPOUND = 0, 1, 8, 9, 10


def _named(tag: int, name: str, payload: bytes) -> bytes:
    nb = name.encode("utf-8")
    return struct.pack(">BH", tag, len(nb)) + nb + payload


def _nbt_string(name: str, value: str) -> bytes:
    b = value.encode("utf-8")
    return _named(_TAG_STRING, name, struct.pack(">H", len(b)) + b)


def _nbt_byte(name: str, value: int) -> bytes:
    return _named(_TAG_BYTE, name, struct.pack(">b", value))


def _compound_body(entries: bytes) -> bytes:
    """Compound payload = entries followed by TAG_End (no name)."""
    return entries + b"\x00"


def build_servers_dat() -> bytes:
    """A vanilla ``servers.dat`` with a single preset BREAKFRONT server."""
    entry = _compound_body(
        _nbt_string("name", SERVER_NAME)
        + _nbt_string("ip", SERVER_IP)
        + _nbt_byte("acceptTextures", 0)
    )
    servers = _named(_TAG_LIST, "servers", struct.pack(">Bi", _TAG_COMPOUND, 1) + entry)
    return _named(_TAG_COMPOUND, "", _compound_body(servers))


# ---------------------------------------------------------------- modpack


def fetch_client_mods() -> tuple[str, list[tuple[str, bytes]]]:
    """Download the breakfront client-mods zip; return (release_tag, [(name, bytes)]).

    The zip's own ``manifest.json`` records an ``env`` (both/server/client) per file;
    server-only mods are dropped so the client pack stays client-clean.
    """
    root = json.loads(http_bytes(UPDATE_BASE + "/"))
    release = root.get("release", "")
    assets = root.get("assets", []) or []
    mods_asset = next(
        (a for a in assets if a.startswith("breakfront-dev-client-mods-") and a.endswith(".zip")),
        None,
    )
    if not mods_asset:
        raise SystemExit(f"[err] no client-mods zip in bfupdate assets: {assets!r}")

    url = f"{UPDATE_BASE}/breakfront/files/{mods_asset}"
    print(f"[mods] {mods_asset} ({release}) <- {url}")
    blob = http_bytes(url)

    jars: list[tuple[str, bytes]] = []
    skipped: list[str] = []
    with zipfile.ZipFile(io.BytesIO(blob)) as zf:
        try:
            manifest = json.loads(zf.read("manifest.json"))
        except Exception:
            manifest = {}
        env_by_path = {
            f["file"]: f.get("env")
            for f in manifest.get("files", [])
            if isinstance(f, dict) and f.get("file")
        }
        for name in zf.namelist():
            if not (name.startswith("mods/") and name.endswith(".jar")):
                continue
            base = os.path.basename(name)
            if env_by_path.get(name) == "server":
                skipped.append(base)
                continue
            jars.append((base, zf.read(name)))

    if not jars:
        raise SystemExit(f"[err] no client mods/*.jar inside {mods_asset}")
    if skipped:
        print(f"[mods] dropped {len(skipped)} server-only: {', '.join(skipped)}")
    return release, jars


def build_modpack(out_dir: Path) -> Path:
    release, jars = fetch_client_mods()
    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": release or "dev",
        "name": PACK_NAME,
        "summary": f"BREAKFRONT client modpack (auto-generated from {release}).",
        "files": [],
        "dependencies": {"minecraft": MC_VERSION, "fabric-loader": FABRIC_LOADER},
    }

    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / "modpack.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("modrinth.index.json", json.dumps(index, ensure_ascii=False, indent=2))
        zf.writestr("overrides/servers.dat", build_servers_dat())
        for name, data in jars:
            zf.writestr("overrides/mods/" + name, data)

    print(f"[ok] {target}  ({len(jars)} mods, {target.stat().st_size / 1024:.0f} KB)")
    for name, _ in jars:
        print("     -", name)
    return target


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="out", help="output directory (default: out)")
    args = ap.parse_args()
    build_modpack(Path(args.out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
