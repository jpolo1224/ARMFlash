#!/usr/bin/env python3
"""Build ARMFlash's PHP for the game server: PHP 7.4 (the major version desktop Flashpoint's
Legacy server runs, php7ts) as CGI from Debian 11's arm64 packages, with mbstring, sqlite3,
pdo_sqlite and the XML extensions, plus glibc 2.39 from the Windows runtime's base system so it
matches the glibc loader the APK ships (libldlinux.so). Self-contained: no Windows runtime is
needed to run it.

Layout (under php/): bin/php-cgi, lib/ (every shared library, flattened), ext/ (extensions),
conf.d/ (the extensions to load), php.ini.

Usage: python3 tools/winrt/packphp.py <work dir> <windows runtime .tar.zst> <out.tar.zst>
Needs the zstandard module."""
import io, lzma, os, re, shutil, sys, tarfile, urllib.request, zstandard, hashlib

# Debian 11's support has ended: its packages and security updates live on Debian's archive.
MIRRORS = [('http://deb.debian.org/debian/', 'bullseye'), ('http://archive.debian.org/debian-security/', 'bullseye-security')]
ROOTS = ['php7.4-cgi', 'php7.4-mbstring', 'php7.4-sqlite3', 'php7.4-xml']
# glibc comes from the runtime (it must match the loader); the rest is not needed to run.
SKIP = {'libc6', 'libc-bin', 'php-common', 'ucf', 'mime-support', 'media-types', 'tzdata', 'psmisc', 'procps',
        'libpam0g', 'libpam-modules', 'libpam-runtime', 'debconf', 'lsb-base', 'sensible-utils', 'libselinux1'}
GLIBC = ['libc.so.6', 'libm.so.6', 'libpthread.so.0', 'libdl.so.2', 'librt.so.1', 'libresolv.so.2', 'libutil.so.1',
         'libanl.so.1', 'libnss_files.so.2', 'libnss_dns.so.2']
EXTENSIONS = ['mbstring', 'sqlite3', 'pdo', 'pdo_sqlite', 'xml', 'dom', 'simplexml', 'xmlreader', 'xmlwriter', 'xsl', 'ctype', 'iconv', 'tokenizer', 'json', 'fileinfo', 'gettext', 'exif', 'calendar', 'ftp', 'phar', 'posix', 'shmop', 'sockets', 'sysvmsg', 'sysvsem', 'sysvshm']

def packages():
    pk = {}
    for base, dist in MIRRORS:
        raw = urllib.request.urlopen(f'{base}dists/{dist}/main/binary-arm64/Packages.xz', timeout=300).read()
        for blk in lzma.decompress(raw).decode('utf-8').split('\n\n'):
            f = dict(m.groups() for m in (re.match(r'^(\S+): (.*)', l) for l in blk.split('\n')) if m)
            if 'Package' in f:
                f['_base'] = base
                pk[f['Package']] = f
    return pk

def closure(pk):
    seen, stack = [], list(ROOTS)
    provides = {}
    for name, f in pk.items():
        for p in f.get('Provides', '').split(','):
            p = re.split(r'[ (]', p.strip())[0]
            if p: provides.setdefault(p, name)
    while stack:
        p = stack.pop()
        p = p if p in pk else provides.get(p, p)
        if p in seen or p in SKIP or p not in pk: continue
        seen.append(p)
        for d in pk[p].get('Depends', '').split(',') + pk[p].get('Pre-Depends', '').split(','):
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
                if m.issym() and os.path.isabs(m.linkname): m.linkname = os.path.basename(m.linkname)
                t.extract(m, dst)

def main(work, runtime, out):
    root = os.path.join(work, 'root'); shutil.rmtree(work, ignore_errors=True); os.makedirs(root)
    pk = packages()
    for p in closure(pk):
        print('  ', p, pk[p]['Version'])
        unpack_deb(urllib.request.urlopen(pk[p]['_base'] + pk[p]['Filename'], timeout=300).read(), root)
    php = os.path.join(work, 'php'); lib = os.path.join(php, 'lib'); ext = os.path.join(php, 'ext')
    for d in ('bin', 'lib', 'ext', 'conf.d'): os.makedirs(os.path.join(php, d))
    shutil.copy2(os.path.join(root, 'usr/bin/php-cgi7.4'), os.path.join(php, 'bin/php-cgi'))
    # Every shared library, flattened into lib/ (symlinks kept as links to the same folder).
    for d in ('lib/aarch64-linux-gnu', 'usr/lib/aarch64-linux-gnu'):
        base = os.path.join(root, d)
        if not os.path.isdir(base): continue
        for n in os.listdir(base):
            src = os.path.join(base, n)
            if '.so' not in n or os.path.isdir(src): continue
            dst = os.path.join(lib, n)
            if os.path.lexists(dst): continue
            if os.path.islink(src): os.symlink(os.path.basename(os.readlink(src)), dst)
            else: shutil.copy2(src, dst)
    extdir = [os.path.join(root, 'usr/lib/php', d) for d in os.listdir(os.path.join(root, 'usr/lib/php')) if d.isdigit()][0]
    for n in os.listdir(extdir): shutil.copy2(os.path.join(extdir, n), os.path.join(ext, n))
    # glibc from the runtime's base system (2.39, the loader's own).
    with zstandard.ZstdDecompressor().stream_reader(open(runtime, 'rb')) as r, tarfile.open(fileobj=r, mode='r|') as t:
        want = {f'r/usr/lib/{n}' for n in GLIBC}; got = {}
        for m in t:
            if m.name in want:
                if m.issym(): got[m.name] = ('link', m.linkname)
                else: got[m.name] = ('file', t.extractfile(m).read())
            elif m.isfile() and m.name.startswith('r/usr/lib/') and any(m.name.endswith(v[1]) for v in got.values() if v[0] == 'link'):
                got[m.name] = ('file', t.extractfile(m).read())
    for name, (kind, v) in got.items():
        if kind == 'file': open(os.path.join(lib, os.path.basename(name)), 'wb').write(v)
    for name, (kind, v) in got.items():
        if kind == 'link':
            target = os.path.join(lib, os.path.basename(v))
            dst = os.path.join(lib, os.path.basename(name))
            if os.path.lexists(dst): os.remove(dst)
            if os.path.isfile(target): shutil.copy2(target, dst)
    # Desktop Flashpoint's php.ini settings that differ from PHP's own defaults, plus what running
    # as the game server's CGI needs (no web server redirect check; the session and upload folders
    # are given on the command line, as they live in the app's own storage).
    open(os.path.join(php, 'php.ini'), 'w').write("""; ARMFlash: desktop Flashpoint's Legacy server settings (its php.ini), for CGI under the game server
engine = On
short_open_tag = Off
precision = 14
output_buffering = 4096
serialize_precision = -1
expose_php = On
max_execution_time = 180
max_input_time = 60
memory_limit = 512M
display_errors = Off
display_startup_errors = Off
log_errors = On
html_errors = Off
variables_order = "GPCS"
request_order = "GP"
register_argc_argv = Off
post_max_size = 8M
default_mimetype = "text/html"
default_charset = ""
file_uploads = On
upload_max_filesize = 2M
allow_url_fopen = On
allow_url_include = Off
default_socket_timeout = 60
date.timezone = UTC
cgi.force_redirect = 0
cgi.fix_pathinfo = 1
session.use_cookies = 1
session.use_only_cookies = 1
""")
    for e in EXTENSIONS:
        if os.path.isfile(os.path.join(ext, e + '.so')):
            prio = '10' if e in ('pdo', 'xml', 'json') else '20'
            open(os.path.join(php, 'conf.d', f'{prio}-{e}.ini'), 'w').write(f'extension={e}.so\n')
    with open(out, 'wb') as f, zstandard.ZstdCompressor(level=19, threads=-1).stream_writer(f) as z, tarfile.open(fileobj=z, mode='w|') as t:
        for base, dirs, files in os.walk(php):
            dirs.sort()
            for n in sorted(files):
                src = os.path.join(base, n); ti = t.gettarinfo(src, os.path.join('php', os.path.relpath(src, php)))
                ti.uid = ti.gid = 0; ti.uname = ti.gname = ''
                if ti.isfile():
                    ti.mode = 0o755 if ('.so' in n or n == 'php-cgi') else 0o644
                    with open(src, 'rb') as fh: t.addfile(ti, fh)
                else: t.addfile(ti)
    print(out, os.path.getsize(out) // 1024, 'KB', hashlib.sha256(open(out, 'rb').read()).hexdigest())

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3])
