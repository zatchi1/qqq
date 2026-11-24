#!/usr/bin/env python3
"""Build res/games.tsv from Discord's public list of detectable games.

Output columns (tab separated, UTF-8): name, windows executable, popularity rank (-1 = not popular), launch arguments
Popular games (data/popular.txt, in that order) come first, the rest alphabetical.
"""
import json
import os
import re
import sys
import urllib.request

URL = "https://discord.com/api/v9/applications/detectable"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BAD = re.compile(r'[<>:"|?*\x00-\x1f]')


def clean_exe(name):
    name = name.strip().lstrip(">").replace("\\", "/")
    if not name or name.startswith("/") or ".." in name.split("/") or BAD.search(name):
        return None
    if not name.lower().endswith(".exe"):
        return None
    return name


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else None
    if src:
        with open(src, "r", encoding="utf-8") as f:
            apps = json.load(f)
    else:
        req = urllib.request.Request(URL, headers={"User-Agent": "Mozilla/5.0 (zatchi build)"})
        with urllib.request.urlopen(req, timeout=120) as r:
            apps = json.load(r)

    popular = []
    with open(os.path.join(ROOT, "data", "popular.txt"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                popular.append(line)
    pop_rank = {}
    for i, n in enumerate(popular):
        pop_rank.setdefault(n.lower(), i)

    rows = {}
    for app in apps:
        name = (app.get("name") or "").replace("\t", " ").replace("\n", " ").strip()
        if not name:
            continue
        exes = []
        for e in app.get("executables") or []:
            if e.get("os") != "win32" or e.get("is_launcher"):
                continue
            c = clean_exe(e.get("name", ""))
            if c:
                args = (e.get("arguments") or "").replace("\t", " ").replace("\n", " ").strip()
                exes.append((c, args))
        if not exes:
            continue
        # prefer entries without required launch arguments, then a plain file name (no folder requirement)
        exes.sort(key=lambda x: (bool(x[1]), x[0].count("/"), len(x[0])))
        exe, exe_args = exes[0]
        rank = pop_rank.get(name.lower(), -1)
        if rank < 0:
            for a in app.get("aliases") or []:
                if a.lower() in pop_rank:
                    rank = pop_rank[a.lower()]
                    break
        key = (name.lower(), exe.lower())
        if key not in rows or rank >= 0:
            rows[key] = (name, exe, rank, exe_args)

    items = list(rows.values())
    items.sort(key=lambda r: (0, r[2], r[0].lower()) if r[2] >= 0 else (1, 0, r[0].lower()))

    out = os.path.join(ROOT, "res", "games.tsv")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        for name, exe, rank, exe_args in items:
            f.write(f"{name}\t{exe}\t{rank}\t{exe_args}\n")

    matched = {r[2] for r in items if r[2] >= 0}
    missing = [n for i, n in enumerate(popular) if i not in matched]
    print(f"games written: {len(items)} (popular matched: {len(matched)}/{len(popular)})")
    if missing:
        print("popular names not found in Discord's list:", "; ".join(missing), file=sys.stderr)
    if len(items) < 500:
        print("ERROR: suspiciously few games, aborting", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
