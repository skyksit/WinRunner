#!/usr/bin/env python3
"""Replace named members inside a .tzst archive with local files.

The container's Wine is shipped as one 64 MB asset, so swapping a module in means
rewriting the whole archive. That was done by hand for `quartz.dll` (app commit
adb353b); this makes it repeatable and checkable, which matters now that the game
speed work replaces the ntdll pair plus wineserver as a matched set.

Every member keeps its original metadata - mode, ownership, mtime, type - and only
its contents and size change. Getting the mode wrong on `wineserver` would leave it
non-executable and the container would not start.

Usage:
    swap_rootfs_members.py app/app/src/main/assets/rootfs.tzst \\
        ./opt/wine/lib/wine/x86_64-unix/ntdll.so=out/lib/wine/x86_64-unix/ntdll.so \\
        ./opt/wine/bin/wineserver=out/bin/wineserver

A leading "./" on the member path is optional; both spellings match.

Modules built against Winlator's tree have `/data/data/com.winlator/...` compiled
into them - the paths are hardcoded in that tree's source, not passed to configure -
so every inserted file is rebranded on the way in. `patch_tzst.py` cannot do it: it
rewrites `com.dgplayer`, this fork's *previous* id, and knows nothing about upstream's.
The substitution works for the same reason that one does - all three ids are 12 bytes,
so nothing that embeds an offset moves.
"""

import io
import os
import sys
import tarfile

import zstandard

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Upstream Winlator's id, as baked into anything built from its Wine tree.
UPSTREAM = b"com.winlator"
# This fork's. Kept in step with applicationId in app/app/build.gradle.
OURS = b"com.retrople"
assert len(UPSTREAM) == len(OURS)


def normalize(name):
    """Members are stored as "./path"; accept either spelling on the command line."""
    return name[2:] if name.startswith("./") else name


def swap(path, replacements):
    """Stream the archive out again, substituting the requested members.

    Returns (out_path, seen) where `seen` maps each member to how many times it
    appeared - anything but 1 means the caller asked for something wrong.
    """
    seen = {k: 0 for k in replacements}
    dctx = zstandard.ZstdDecompressor()
    # Same level patch_tzst.py uses: close to upstream's archive sizes and still a
    # standard frame (window log <= 27) the on-device zstd-jni decoder accepts.
    cctx = zstandard.ZstdCompressor(level=22)
    out_path = path + ".swapped"
    with open(path, "rb") as fin, dctx.stream_reader(fin) as zr, \
            tarfile.open(fileobj=zr, mode="r|") as src, \
            open(out_path, "wb") as fout, \
            cctx.stream_writer(fout, closefd=False) as zw, \
            tarfile.open(fileobj=zw, mode="w|", format=tarfile.GNU_FORMAT) as dst:
        for m in src:
            key = normalize(m.name)
            if key in replacements and m.isreg():
                seen[key] += 1
                with open(replacements[key], "rb") as f:
                    buf = f.read()
                rebranded = buf.count(UPSTREAM)
                if rebranded:
                    buf = buf.replace(UPSTREAM, OURS)
                old_size = m.size
                m.size = len(buf)
                note = f", {rebranded} path(s) rebranded" if rebranded else ""
                print(f"    {key}: {old_size} -> {m.size} bytes (mode {m.mode:o}{note})")
                dst.addfile(m, io.BytesIO(buf))
            elif m.isreg():
                dst.addfile(m, src.extractfile(m))
            else:
                dst.addfile(m)
    return out_path, seen


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2

    archive = argv[1]
    if not os.path.isfile(archive):
        print(f"no such archive: {archive}")
        return 1

    replacements = {}
    for arg in argv[2:]:
        if "=" not in arg:
            print(f"expected <member>=<file>, got: {arg}")
            return 2
        member, local = arg.split("=", 1)
        if not os.path.isfile(local):
            print(f"no such file: {local}")
            return 1
        replacements[normalize(member)] = local

    print(f"{archive}: replacing {len(replacements)} member(s)")
    out_path, seen = swap(archive, replacements)

    missing = [k for k, n in seen.items() if n == 0]
    duplicated = [k for k, n in seen.items() if n > 1]
    if missing or duplicated:
        for k in missing:
            print(f"  NOT FOUND: {k}")
        for k in duplicated:
            print(f"  FOUND {seen[k]} TIMES: {k}")
        os.remove(out_path)
        print("\nFAILED - the original is untouched.")
        return 1

    os.replace(out_path, archive)
    print("\nDone.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
