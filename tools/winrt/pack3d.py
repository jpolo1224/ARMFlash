#!/usr/bin/env python3
"""Build ARMFlash's software 3D pack for Windows players: Mesa (llvmpipe) for glibc arm64,
from Ubuntu 24.04's packages (the runtime's glibc is 2.39, the same as Ubuntu 24.04's).

Wine's Direct3D needs OpenGL; Box64 hands Wine's x86 code the native arm64 libGL, and Mesa's
llvmpipe draws on the CPU into the virtual screen. Everything lands in r/usr/lib/3d (the
runtime puts that after its own lib folder, so the base system's copies of shared libraries
win), with the DRI drivers in r/usr/lib/3d/dri.

Usage: python3 tools/winrt/pack3d.py <work dir> <out.tar.zst>
Needs the zstandard module (pip install zstandard)."""
import io, lzma, os, re, sys, tarfile, urllib.request, zstandard, hashlib, shutil

MIRROR = 'http://ports.ubuntu.com/ubuntu-ports/'
ROOTS = ['libgl1-mesa-dri', 'libglx-mesa0', 'libgl1', 'libglx0', 'libglvnd0']
# In the runtime's base system already, or not wanted.
SKIP = {'libc6', 'libgcc-s1', 'libstdc++6', 'zlib1g', 'libx11-data', 'libdrm-common', 'libsensors-config'}
PREFIX = 'r/usr/lib/3d'

def packages():
    pk = {}
    for dist in ('noble', 'noble-updates'):
        raw = urllib.request.urlopen(f'{MIRROR}dists/{dist}/main/binary-arm64/Packages.xz', timeout=300).read()
        for blk in lzma.decompress(raw).decode('utf-8').split('\n\n'):
            f = dict(m.groups() for m in (re.match(r'^(\S+): (.*)', l) for l in blk.split('\n')) if m)
            if 'Package' in f: pk[f['Package']] = f
    return pk

def closure(pk):
    seen, stack = [], list(ROOTS)
    while stack:
        p = stack.pop()
        if p in seen or p in SKIP or p not in pk: continue
        seen.append(p)
        for d in pk[p].get('Depends', '').split(','):
            if d.strip(): stack.append(re.split(r'[ (:]', d.split('|')[0].strip())[0])
    return seen

def unpack_deb(data, dst):
    assert data[:8] == b'!<arch>\n'
    p = 8
    while p < len(data):
        name = data[p:p + 16].decode().strip().rstrip('/'); size = int(data[p + 48:p + 58])
        body = data[p + 60:p + 60 + size]; p += 60 + size + (size & 1)
        if not name.startswith('data.tar'): continue
        raw = zstandard.ZstdDecompressor().stream_reader(io.BytesIO(body)).read() if name.endswith('.zst') else \
            lzma.decompress(body) if name.endswith('.xz') else body
        with tarfile.open(fileobj=io.BytesIO(raw)) as t:
            for m in t.getmembers():
                n = os.path.normpath(m.name)
                if n.startswith('..') or os.path.isabs(n) or m.isdev(): continue
                if m.issym() and os.path.isabs(m.linkname): continue
                t.extract(m, dst)

def main(work, out):
    os.makedirs(work, exist_ok=True)
    root = os.path.join(work, 'root'); shutil.rmtree(root, ignore_errors=True); os.makedirs(root)
    pk = packages()
    for p in closure(pk):
        print('  ', p, pk[p]['Version'])
        unpack_deb(urllib.request.urlopen(MIRROR + pk[p]['Filename'], timeout=300).read(), root)
    lib = os.path.join(root, 'usr/lib/aarch64-linux-gnu')
    with open(out, 'wb') as f:
        with zstandard.ZstdCompressor(level=19, threads=-1).stream_writer(f) as z:
            with tarfile.open(fileobj=z, mode='w|') as t:
                for base, dirs, files in os.walk(lib):
                    dirs.sort()
                    for n in sorted(files):
                        src = os.path.join(base, n); rel = os.path.relpath(src, lib)
                        if rel.startswith(('pkgconfig', 'cmake')) or n.endswith(('.a', '.la')): continue
                        ti = t.gettarinfo(src, f'{PREFIX}/{rel}')
                        # Libraries as the runtime's own are (0755), owned by nobody in particular.
                        if ti.isfile(): ti.mode = 0o755 if '.so' in n else 0o644
                        ti.uid = ti.gid = 0; ti.uname = ti.gname = ''
                        if ti.isfile():
                            with open(src, 'rb') as fh: t.addfile(ti, fh)
                        else: t.addfile(ti)
                share = os.path.join(root, 'usr/share')
                for sub in ('glvnd', 'drirc.d', 'vulkan'):
                    d = os.path.join(share, sub)
                    if os.path.isdir(d): t.add(d, f'{PREFIX}/share/{sub}')
    print(out, os.path.getsize(out) // 1048576, 'MB', hashlib.sha256(open(out, 'rb').read()).hexdigest())

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
