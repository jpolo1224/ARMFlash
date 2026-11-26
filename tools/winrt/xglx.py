#!/usr/bin/env python3
"""GLX for the virtual screen. Termux's Xvfb (x/bin/Xvfb) has GLX built in, but it loads its
software renderer from x/lib/dri/swrast_dri.so, which the runtime never had: with none, the X
server offers no GL visuals, so no Windows program gets OpenGL, nor Direct3D on top of it.

Mesa's libdril is the stub Mesa makes for exactly this: an X server that still loads a DRI driver
gets a fixed list of framebuffer configs and nothing else (it renders nothing; clients render
in their own process, here Wine with the 3D pack's llvmpipe, and the server only shows the
result). It reads the configs from EGL only when it can load libEGL.so.1, which Xvfb cannot, so it
needs neither Mesa's renderer nor LLVM: only libgbm and libdrm, for linking.

Takes them from Termux's mesa and libdrm packages (bionic, aarch64, the same build family as the
Xvfb), moves their Termux prefix to ARMFlash's x/ (same length, as for the Xvfb), and writes them
to the APK's assets; WinRuntime.ensureGlx puts them in x/lib.

Usage: python3 tools/winrt/xglx.py <work dir>
"""
import hashlib, io, lzma, os, sys, tarfile, urllib.request

POOL = 'https://packages.termux.dev/apt/termux-main/pool/main/'
DEBS = {
    'm/mesa/mesa_26.3.1_aarch64.deb': '39311a06e99baa28cd05b83675d20fb7a0726d7aa98610bf30910e2695551311',
    'libd/libdrm/libdrm_2.4.134_aarch64.deb': '0cbe82140cf325c15473fbf53a3711fc7a2fb88e36a3eea58a3267c461ab4a17',
}
# Path in the package -> asset name.
FILES = {
    'data/data/com.termux/files/usr/lib/dri/libdril_dri.so': 'swrast_dri.so',
    'data/data/com.termux/files/usr/lib/libgbm.so': 'libgbm.so',
    'data/data/com.termux/files/usr/lib/libdrm.so': 'libdrm.so',
}
OLD = b'/data/data/com.termux/files/usr'
NEW = b'/data/data/com.armsx.flasharm/x'
assert len(OLD) == len(NEW)
ASSETS = os.path.join(os.path.dirname(__file__), '..', '..', 'android', 'app', 'src', 'main', 'assets', 'winrt', 'xglx')


def deb_data(deb):
    """The data.tar members of a .deb (an ar archive)."""
    assert deb[:8] == b'!<arch>\n'
    i = 8
    while i < len(deb):
        name = deb[i:i + 16].decode().strip().rstrip('/')
        size = int(deb[i + 48:i + 58].decode().strip())
        i += 60
        body = deb[i:i + size]
        i += size + (size & 1)
        if name == 'data.tar.xz':
            return tarfile.open(fileobj=io.BytesIO(lzma.decompress(body)))
    raise SystemExit('no data.tar.xz')


def main():
    work = sys.argv[1]
    os.makedirs(work, exist_ok=True)
    os.makedirs(ASSETS, exist_ok=True)
    for path, sha in DEBS.items():
        local = os.path.join(work, os.path.basename(path))
        if not os.path.isfile(local):
            urllib.request.urlretrieve(POOL + path, local)
        deb = open(local, 'rb').read()
        assert hashlib.sha256(deb).hexdigest() == sha, f'{local}: checksum'
        tar = deb_data(deb)
        for m in tar.getmembers():
            name = m.name.lstrip('./')
            if name not in FILES or not m.isfile():
                continue
            data = bytes(tar.extractfile(m).read()).replace(OLD, NEW)
            out = os.path.join(ASSETS, FILES[name])
            open(out, 'wb').write(data)
            print(FILES[name], len(data), hashlib.sha256(data).hexdigest())


if __name__ == '__main__':
    main()
