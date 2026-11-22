"""Relocate Winlator's base system to ARMFlash's: rewrite every NUL-terminated string that starts
with OLD to start with NEW (shorter), padding with NULs. Reads a tar, writes a tar of changed files."""
import sys, tarfile, io
OLD = b"/data/data/com.winlator/files/rootfs"
NEW = b"/data/data/com.armsx.flasharm/r"
assert len(NEW) <= len(OLD)
src, dst = sys.argv[1], sys.argv[2]
out = tarfile.open(dst, "w")
n_files = n_strings = 0
with tarfile.open(src, "r") as t:
    for m in t:
        if not m.isfile() or m.size == 0:
            continue
        data = t.extractfile(m).read()
        if OLD not in data:
            continue
        if b"\0" not in data:
            # A text file (scripts, configs): an ordinary replace.
            b = bytearray(data.replace(OLD, NEW))
            n_strings += data.count(OLD)
            info = tarfile.TarInfo(m.name); info.size = len(b); info.mode = m.mode; info.mtime = m.mtime
            out.addfile(info, io.BytesIO(bytes(b))); n_files += 1
            continue
        b = bytearray(data)
        i = 0
        while True:
            i = b.find(OLD, i)
            if i < 0:
                break
            end = b.find(0, i)
            if end < 0:
                end = len(b)
            s = bytes(b[i:end])
            r = NEW + s[len(OLD):]
            b[i:end] = r + b"\0" * (len(s) - len(r))
            n_strings += 1
            i = end
        assert len(b) == len(data)
        info = tarfile.TarInfo(m.name); info.size = len(b); info.mode = m.mode; info.mtime = m.mtime
        out.addfile(info, io.BytesIO(bytes(b)))
        n_files += 1
out.close()
print(f"{n_files} files, {n_strings} strings relocated")
