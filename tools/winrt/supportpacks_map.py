#!/usr/bin/env python3
"""Regenerate android/app/src/main/assets/flashpoint/supportpacks.json from Flashpoint's
component index: every support pack's dependencies, title and top-level files and folders
(read from each zip's directory with HTTP range requests, nothing downloaded in full).
ARMFlash uses it to find the packs a game's start script, program folder or platform needs.

Usage: python3 -I tools/winrt/supportpacks_map.py <out.json>"""
import io, json, re, sys, urllib.request, zipfile, xml.etree.ElementTree as ET, concurrent.futures as cf

BASE = 'https://nexus-dev.unstable.life/repository/stable/'
UA = {'User-Agent': 'ARMFlash'}

class Remote(io.RawIOBase):
    def __init__(self, url):
        self.url, self.pos = url, 0
        head = urllib.request.Request(url, method='HEAD', headers=UA)
        self.size = int(urllib.request.urlopen(head, timeout=60).headers['Content-Length'])
    def seekable(self): return True
    def readable(self): return True
    def seek(self, off, whence=0):
        self.pos = {0: off, 1: self.pos + off, 2: self.size + off}[whence]; return self.pos
    def tell(self): return self.pos
    def readinto(self, b):
        if self.pos >= self.size: return 0
        end = min(self.size, self.pos + len(b)) - 1
        req = urllib.request.Request(self.url, headers={**UA, 'Range': f'bytes={self.pos}-{end}'})
        data = urllib.request.urlopen(req, timeout=60).read()
        b[:len(data)] = data; self.pos += len(data); return len(data)

def main(out):
    root = ET.fromstring(urllib.request.urlopen(urllib.request.Request(BASE + 'components.xml', headers=UA), timeout=60).read())
    packs = {}
    def walk(el, prefix):
        for c in el:
            if c.tag == 'category': walk(c, prefix + [c.get('id')])
            elif c.tag == 'component':
                fid = '-'.join(prefix + [c.get('id')])
                if fid.startswith('supportpack-'):
                    packs[fid] = {'title': c.get('title'), 'path': c.get('path') or '',
                                  'depends': (c.get('depends') or '').split(), 'size': int(c.get('download-size') or 0)}
    walk(root, [])
    def top(fid):
        z = zipfile.ZipFile(io.BufferedReader(Remote(BASE + fid + '.zip'), buffer_size=1 << 16))
        names = z.namelist()
        # Secure Player templates that run in Internet Explorer's browser control (WEB_BROWSER,
        # the default mode): their games need Wine's Gecko. Secure Player's own generic
        # "internetexplorer" template is left out, as every pack depends on that pack.
        web = []
        for n in names:
            if n.startswith('FlashpointSecurePlayerConfigs/') and n.endswith('.config') and fid != 'supportpack-common-secureplayer':
                t = z.read(n).decode('utf-8', 'replace')
                modes = [m for m in re.findall(r'<mode\b[^>]*>', t)]
                if not modes or any('SOFTWARE' not in m for m in modes):
                    web.append(n.split('/')[-1][:-7])
        return fid, sorted({n.split('/')[0] for n in names if n.split('/')[0] and n.split('/')[0] != 'FlashpointSecurePlayerConfigs'}), sorted(web)
    with cf.ThreadPoolExecutor(8) as ex:
        for fid, t, web in ex.map(top, packs):
            packs[fid]['top'] = t
            if web: packs[fid]['web'] = web
    json.dump({'packs': packs}, open(out, 'w'), separators=(',', ':'), sort_keys=True)
    print(len(packs), 'packs')

if __name__ == '__main__':
    main(sys.argv[1])
