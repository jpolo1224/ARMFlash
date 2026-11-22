#!/usr/bin/env python3
"""Pack ARMFlash's Windows runtime: winrt.tar.zst, unpacked by the app into its data folder.

  r/  the relocated Linux base (glibc, Wine), from relocate.py's output tar
  x/  the X server: Xvfb, the libraries it loads (found by following DT_NEEDED) and xkb data

usage: pack.py <rootfs.tar> <relocated.tar> <x tree> <out.tar.zst> <llvm-readelf>
  rootfs.tar is the base system as published; relocated.tar holds relocate.py's rewritten
  files, which replace their originals.
Needs the `zstandard` module. Prints the SHA-256 and size for the download manifest.
"""
import hashlib, io, os, subprocess, sys, tarfile
import zstandard

base_tar, rel_tar, xdir, out, readelf = sys.argv[1:6]

def needed(path):
    o = subprocess.run([readelf, "-d", path], capture_output=True, text=True).stdout
    return [l.split("[")[1].split("]")[0] for l in o.splitlines() if "(NEEDED)" in l]

# The X server's library closure (Android's own libraries are not in lib/ and are skipped).
keep, todo = set(), ["bin/Xvfb", "bin/xkbcomp"]
while todo:
    f = todo.pop()
    if f in keep or not os.path.lexists(os.path.join(xdir, f)):
        continue
    keep.add(f)
    real = os.path.realpath(os.path.join(xdir, f))
    if os.path.islink(os.path.join(xdir, f)):
        todo.append(os.path.relpath(real, xdir))
    for n in needed(real):
        todo.append("lib/" + n)
for d in ["share/X11", "share/xkeyboard-config-2"]:
    for base, dirs, files in os.walk(os.path.join(xdir, d)):
        for n in dirs + files:
            keep.add(os.path.relpath(os.path.join(base, n), xdir))
keep.discard("bin/xkbcomp")  # the app links this to its own copy in the APK
keep.add("share")

cctx = zstandard.ZstdCompressor(level=19, threads=-1)
with open(out, "wb") as fh, cctx.stream_writer(fh) as zw, tarfile.open(fileobj=zw, mode="w|") as tw:
    def norm(n):
        return n[2:] if n.startswith("./") else n.lstrip("/")
    with tarfile.open(rel_tar) as rel:
        replaced = {norm(m.name) for m in rel.getmembers()}
    for path, skip in ((base_tar, replaced), (rel_tar, set())):
        with tarfile.open(path) as src:
            for m in src:
                name = norm(m.name)
                if not name or name in skip or name.split("/")[0] in ("home", "tmp", "ld"):
                    continue
                m.name = "r/" + name
                if m.islnk():
                    m.linkname = "r/" + norm(m.linkname)
                tw.addfile(m, src.extractfile(m) if m.isfile() else None)
    for rel in sorted(keep):
        p = os.path.join(xdir, rel)
        ti = tw.gettarinfo(p, "x/" + rel)
        ti.uid = ti.gid = 0; ti.uname = ti.gname = ""
        if ti.isfile():
            with open(p, "rb") as f:
                tw.addfile(ti, f)
        else:
            tw.addfile(ti)

h = hashlib.sha256()
with open(out, "rb") as f:
    for b in iter(lambda: f.read(1 << 20), b""):
        h.update(b)
print(out, os.path.getsize(out), h.hexdigest())
