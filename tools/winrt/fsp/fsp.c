/*
 * ARMFlash's stand-in for Flashpoint Secure Player, for Windows players under Wine.
 *
 * Flashpoint's start scripts (startUnity.bat, startJava.bat, startNetscape.bat and the rest) run
 * "FlashpointSecurePlayer <template> <url> [arguments]". The real one is a .NET program, and
 * ARMFlash's Wine has no .NET. This reads the same per-template configs
 * (FlashpointSecurePlayerConfigs\<template>.config) and does what they ask the way Flashpoint
 * Secure Player does: set the FP_* variables and the template's environment variables, write
 * its registry values (in the 32-bit view for 32-bit players), then start the mode's program
 * and wait for it. Behaviour follows Flashpoint Secure Player's source
 * (FlashpointProject/FlashpointSecureTools, MIT, by tomysshadow); no code is taken from it.
 *
 * WEB_BROWSER mode shows the page in Internet Explorer's browser control (Wine's, with Wine
 * Gecko) in a bare full-screen window. Left out, as the virtual Windows exists only for games:
 * reverting the registry afterwards, the Old CPU Simulator (the program runs at full speed),
 * single-instance prompts and downloads ahead of time.
 *
 * Build (32-bit, for the 32-bit ActiveX controls its browser mode hosts):
 *   python3 -m ziglang cc -target x86-windows-gnu -O2 -municode fsp.c \
 *        -o FlashpointSecurePlayer.exe -ladvapi32 -lshell32 -lole32 -luser32
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <ole2.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

static void say(const wchar_t *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    fwprintf(stderr, L"armflash-fsp: "); vfwprintf(stderr, fmt, ap); fwprintf(stderr, L"\n");
    va_end(ap); fflush(stderr);
}

/* ---- the raw command line, split into tokens with their start offsets ------------------ */

/* Start of each argument in the raw command line (Windows' quoting: "..." groups, \" escapes). */
static int token_starts(const wchar_t *cl, size_t *starts, int max) {
    int n = 0; const wchar_t *p = cl;
    while (*p) {
        while (*p == L' ' || *p == L'\t') p++;
        if (!*p || n >= max) break;
        starts[n++] = (size_t)(p - cl);
        int quoted = 0;
        while (*p && (quoted || (*p != L' ' && *p != L'\t'))) {
            if (*p == L'\\' && p[1] == L'"') { p += 2; continue; }
            if (*p == L'"') quoted = !quoted;
            p++;
        }
    }
    return n;
}

/* ---- the config: a small reader for the few elements and attributes it uses ------------- */

static wchar_t *read_utf8(const wchar_t *path) {
    FILE *f = _wfopen(path, L"rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *buf = malloc(n + 1); if (!buf) { fclose(f); return NULL; }
    n = (long)fread(buf, 1, n, f); fclose(f); buf[n] = 0;
    const char *s = buf; if (n >= 3 && (unsigned char)s[0] == 0xEF && (unsigned char)s[1] == 0xBB && (unsigned char)s[2] == 0xBF) s += 3;
    int w = MultiByteToWideChar(CP_UTF8, 0, s, -1, NULL, 0);
    wchar_t *out = malloc(w * sizeof(wchar_t));
    MultiByteToWideChar(CP_UTF8, 0, s, -1, out, w);
    free(buf);
    return out;
}

/* XML entity decoding, in place (&quot; &amp; &lt; &gt; &apos; &#N; &#xN;). */
static void unescape(wchar_t *s) {
    wchar_t *o = s;
    while (*s) {
        if (*s == L'&') {
            wchar_t *e = wcschr(s, L';');
            if (e) {
                size_t k = (size_t)(e - s - 1); const wchar_t *n = s + 1; long c = -1;
                if (k == 4 && !wcsncmp(n, L"quot", 4)) c = L'"';
                else if (k == 3 && !wcsncmp(n, L"amp", 3)) c = L'&';
                else if (k == 2 && !wcsncmp(n, L"lt", 2)) c = L'<';
                else if (k == 2 && !wcsncmp(n, L"gt", 2)) c = L'>';
                else if (k == 4 && !wcsncmp(n, L"apos", 4)) c = L'\'';
                else if (k > 1 && n[0] == L'#') c = (n[1] == L'x' || n[1] == L'X') ? wcstol(n + 2, NULL, 16) : wcstol(n + 1, NULL, 10);
                if (c >= 0) { *o++ = (wchar_t)c; s = e + 1; continue; }
            }
        }
        *o++ = *s++;
    }
    *o = 0;
}

/* The value of attribute [name] in the tag starting at [tag] (up to its '>'), or NULL. */
static wchar_t *attr(const wchar_t *tag, const wchar_t *name) {
    const wchar_t *end = wcschr(tag, L'>');
    size_t nl = wcslen(name);
    for (const wchar_t *p = tag; p && p < end; p++) {
        if ((p[-1] == L' ' || p[-1] == L'\t' || p[-1] == L'\n' || p[-1] == L'\r') && !wcsncmp(p, name, nl)) {
            const wchar_t *q = p + nl;
            while (*q == L' ') q++;
            if (*q != L'=') continue;
            q++; while (*q == L' ') q++;
            wchar_t quote = *q; if (quote != L'"' && quote != L'\'') continue;
            const wchar_t *v = q + 1, *ve = wcschr(v, quote);
            if (!ve || ve > end) return NULL;
            wchar_t *out = malloc((ve - v + 1) * sizeof(wchar_t));
            wmemcpy(out, v, ve - v); out[ve - v] = 0;
            unescape(out);
            return out;
        }
    }
    return NULL;
}

/* The next tag named [name] at or after [from] and before [limit], or NULL. */
static const wchar_t *next_tag(const wchar_t *from, const wchar_t *limit, const wchar_t *name) {
    size_t nl = wcslen(name);
    for (const wchar_t *p = from; (p = wcschr(p, L'<')) && p < limit; p++) {
        if (!wcsncmp(p + 1, name, nl) && (p[1 + nl] == L' ' || p[1 + nl] == L'>' || p[1 + nl] == L'/' || p[1 + nl] == L'\t' || p[1 + nl] == L'\n' || p[1 + nl] == L'\r'))
            return p;
    }
    return NULL;
}

static wchar_t *expand(const wchar_t *s) {
    DWORD n = ExpandEnvironmentStringsW(s, NULL, 0);
    wchar_t *out = malloc((n + 1) * sizeof(wchar_t));
    ExpandEnvironmentStringsW(s, out, n + 1);
    return out;
}

/* ---- registry ------------------------------------------------------------------------- */

static int b64(wchar_t c) {
    if (c >= L'A' && c <= L'Z') return c - L'A';
    if (c >= L'a' && c <= L'z') return c - L'a' + 26;
    if (c >= L'0' && c <= L'9') return c - L'0' + 52;
    if (c == L'+') return 62; if (c == L'/') return 63;
    return -1;
}

static void set_registry(const wchar_t *tag, REGSAM view) {
    wchar_t *type = attr(tag, L"type"), *key = attr(tag, L"keyName"), *vname = attr(tag, L"valueName");
    wchar_t *raw = attr(tag, L"value"), *kind = attr(tag, L"valueKind");
    if (!key) goto done;
    HKEY root; const wchar_t *sub;
    struct { const wchar_t *p; HKEY k; } roots[] = {
        { L"HKEY_CURRENT_USER\\", HKEY_CURRENT_USER }, { L"HKEY_LOCAL_MACHINE\\", HKEY_LOCAL_MACHINE },
        { L"HKEY_CLASSES_ROOT\\", HKEY_CLASSES_ROOT }, { L"HKEY_USERS\\", HKEY_USERS }, { L"HKEY_CURRENT_CONFIG\\", HKEY_CURRENT_CONFIG },
    };
    root = NULL; sub = NULL;
    for (size_t i = 0; i < sizeof roots / sizeof *roots; i++)
        if (!_wcsnicmp(key, roots[i].p, wcslen(roots[i].p))) { root = roots[i].k; sub = key + wcslen(roots[i].p); break; }
    if (!root) { say(L"skipped registry key %ls", key); goto done; }
    HKEY h;
    if (RegCreateKeyExW(root, sub, 0, NULL, 0, KEY_WRITE | view, NULL, &h, NULL) != ERROR_SUCCESS) { say(L"could not open %ls", key); goto done; }
    if (type && !_wcsicmp(type, L"VALUE")) {
        wchar_t *v = expand(raw ? raw : L"");
        const wchar_t *k = kind ? kind : L"String";
        LONG r;
        if (!_wcsicmp(k, L"DWord")) {
            DWORD d = (DWORD)wcstol(v, NULL, 10);
            r = RegSetValueExW(h, vname, 0, REG_DWORD, (BYTE *)&d, sizeof d);
        } else if (!_wcsicmp(k, L"QWord")) {
            long long q = wcstoll(v, NULL, 10);
            r = RegSetValueExW(h, vname, 0, REG_QWORD, (BYTE *)&q, sizeof q);
        } else if (!_wcsicmp(k, L"Binary")) {
            size_t n = wcslen(v); BYTE *b = malloc(n); size_t o = 0; int acc = 0, bits = 0;
            for (size_t i = 0; i < n; i++) { int c = b64(v[i]); if (c < 0) continue; acc = (acc << 6) | c; bits += 6; if (bits >= 8) { bits -= 8; b[o++] = (BYTE)(acc >> bits); } }
            r = RegSetValueExW(h, vname, 0, REG_BINARY, b, (DWORD)o);
            free(b);
        } else if (!_wcsicmp(k, L"MultiString")) {
            /* NUL-separated in the config; REG_MULTI_SZ wants a second NUL at the end. */
            size_t n = wcslen(v); wchar_t *m = calloc(n + 2, sizeof(wchar_t)); wmemcpy(m, v, n);
            r = RegSetValueExW(h, vname, 0, REG_MULTI_SZ, (BYTE *)m, (DWORD)((n + 2) * sizeof(wchar_t)));
            free(m);
        } else {
            DWORD t = !_wcsicmp(k, L"ExpandString") ? REG_EXPAND_SZ : REG_SZ;
            r = RegSetValueExW(h, vname, 0, t, (BYTE *)v, (DWORD)((wcslen(v) + 1) * sizeof(wchar_t)));
        }
        if (r != ERROR_SUCCESS) say(L"could not set %ls\\%ls", key, vname ? vname : L"");
        free(v);
    }
    RegCloseKey(h);
done:
    free(type); free(key); free(vname); free(raw); free(kind);
}

/* ---- the htdocs file for a URL (FP_HTDOCS_FILE) ---------------------------------------- */

static void set_htdocs(const wchar_t *startup, const wchar_t *url) {
    const wchar_t *p = wcsstr(url, L"://");
    if (!p) { SetEnvironmentVariableW(L"FP_HTDOCS_FILE", L" "); SetEnvironmentVariableW(L"FP_HTDOCS_FILE_DIR", L" "); return; }
    p += 3;
    const wchar_t *slash = wcschr(p, L'/');
    size_t hl = slash ? (size_t)(slash - p) : wcslen(p);
    wchar_t host[512] = {0}; wcsncpy(host, p, hl < 511 ? hl : 511);
    wchar_t *colon = wcschr(host, L':'); if (colon) *colon = 0;
    wchar_t path[2048] = {0};
    if (slash) { wcsncpy(path, slash, 2047); wchar_t *q = wcspbrk(path, L"?#"); if (q) *q = 0; }
    for (wchar_t *c = path; *c; c++) if (*c == L'/') *c = L'\\';
    wchar_t rel[4096];
    if (!_wcsicmp(host, L"localhost")) swprintf(rel, 4096, L"%ls\\..\\Legacy\\htdocs%ls", startup, path);
    else swprintf(rel, 4096, L"%ls\\..\\Legacy\\htdocs\\%ls%ls", startup, host, path);
    wchar_t full[4096]; GetFullPathNameW(rel, 4096, full, NULL);
    size_t fl = wcslen(full);
    if (fl == 0 || full[fl - 1] == L'\\') wcscat(full, L"index.html");
    SetEnvironmentVariableW(L"FP_HTDOCS_FILE", full);
    wchar_t *last = wcsrchr(full, L'\\'); if (last) *last = 0;
    SetEnvironmentVariableW(L"FP_HTDOCS_FILE_DIR", full);
}

/* ---- WEB_BROWSER mode: Internet Explorer's browser control in a bare full-screen window --- */

/* Flashpoint Secure Player shows these pages in a WinForms WebBrowser (Internet Explorer's
   control) with no toolbars, maximized. Wine's ATL does the same with a window of class
   AtlAxWin named after the URL: it hosts the browser control and navigates there. ActiveX
   controls on the page load into this process, so it is a 32-bit program. */
static int web_browser(const wchar_t *tpl, const wchar_t *url) {
    HMODULE atl = LoadLibraryW(L"atl.dll");
    BOOL (WINAPI *init)(void) = atl ? (BOOL (WINAPI *)(void))GetProcAddress(atl, "AtlAxWinInit") : NULL;
    if (!init || !init()) { say(L"%ls: no browser control (atl.dll)", tpl); return 2; }
    OleInitialize(NULL);
    int w = GetSystemMetrics(SM_CXSCREEN), h = GetSystemMetrics(SM_CYSCREEN);
    say(L"%ls: browser on %ls (%dx%d)", tpl, url, w, h);
    HWND win = CreateWindowExW(0, L"AtlAxWin", url, WS_POPUP | WS_VISIBLE | WS_CLIPCHILDREN, 0, 0, w, h, NULL, NULL, GetModuleHandleW(NULL), NULL);
    if (!win) { say(L"%ls: could not open the browser window (error %lu)", tpl, GetLastError()); return 1; }
    for (;;) {
        MSG msg;
        while (PeekMessageW(&msg, NULL, 0, 0, PM_REMOVE)) {
            if (msg.message == WM_QUIT) goto done;
            TranslateMessage(&msg); DispatchMessageW(&msg);
        }
        if (!IsWindow(win)) break;
        MsgWaitForMultipleObjects(0, NULL, FALSE, 250, QS_ALLINPUT);
    }
done:
    say(L"%ls ended (browser closed)", tpl);
    OleUninitialize();
    return 0;
}

int wmain(int argc, wchar_t **argv) {
    (void)argv;
    const wchar_t *cl = GetCommandLineW();
    size_t starts[256]; int n = token_starts(cl, starts, 256);
    int wargc; wchar_t **wargv = CommandLineToArgvW(cl, &wargc);
    if (!wargv || wargc < 3 || argc < 3) { say(L"usage: FlashpointSecurePlayer <template> <url> [arguments]"); return 1; }

    wchar_t startup[MAX_PATH]; GetModuleFileNameW(NULL, startup, MAX_PATH);
    wchar_t *bs = wcsrchr(startup, L'\\'); if (bs) *bs = 0;
    SetCurrentDirectoryW(startup);

    /* Switches FSP knows come first; the first other argument starts FP_ARGUMENTS. */
    const wchar_t *arguments = L"";
    for (int i = 3; i < wargc && i < n; i++) {
        const wchar_t *a = wargv[i];
        if (!_wcsicmp(a, L"--arguments") || !_wcsicmp(a, L"-args")) { if (i + 1 < n) arguments = cl + starts[i + 1]; break; }
        if (!_wcsicmp(a, L"--download-before") || !_wcsicmp(a, L"-dlb")) { i++; continue; }
        if (!_wcsicmp(a, L"--run-as-administrator") || !_wcsicmp(a, L"-a") || !_wcsnicmp(a, L"--dev-", 6) ||
            !_wcsicmp(a, L"--activex") || !_wcsicmp(a, L"-ax")) continue;
        arguments = cl + starts[i]; break;
    }

    SetEnvironmentVariableW(L"FP_STARTUP_PATH", startup);
    SetEnvironmentVariableW(L"FP_URL", wargv[2]);
    SetEnvironmentVariableW(L"FP_ARGUMENTS", *arguments ? arguments : L" ");
    set_htdocs(startup, wargv[2]);

    /* The template's config: lower-cased name, characters a file name cannot hold made dots. */
    wchar_t name[512]; size_t k = 0;
    for (const wchar_t *c = wargv[1]; *c && k < 500; c++) {
        wchar_t ch = towlower(*c);
        if (wcschr(L"<>:\"/\\|?*", ch) || ch < 32) { if (!k || name[k - 1] != L'.') name[k++] = L'.'; }
        else name[k++] = ch;
    }
    name[k] = 0;
    wchar_t cfgpath[MAX_PATH * 2];
    swprintf(cfgpath, MAX_PATH * 2, L"%ls\\FlashpointSecurePlayerConfigs\\%ls.config", startup, name);
    wchar_t *cfg = read_utf8(cfgpath);
    if (!cfg) {
        swprintf(cfgpath, MAX_PATH * 2, L"%ls\\..\\Legacy\\htdocs\\flashpointsecureplayerconfigs\\%ls.config", startup, name);
        cfg = read_utf8(cfgpath);
    }
    if (!cfg) { say(L"no config for template %ls", wargv[1]); return 1; }

    /* <template name="..."> ... </template> */
    const wchar_t *end = cfg + wcslen(cfg), *tpl = NULL;
    for (const wchar_t *t = cfg; (t = next_tag(t, end, L"template")); t++) {
        wchar_t *tn = attr(t, L"name");
        int match = tn && !_wcsicmp(tn, wargv[1]);
        free(tn);
        if (match) { tpl = t; break; }
    }
    if (!tpl) { say(L"template %ls not in its config", wargv[1]); return 1; }
    const wchar_t *tend = wcsstr(tpl, L"</template>"); if (!tend) tend = end;

    /* Environment variables, then registry values, as FSP orders them. */
    for (const wchar_t *e = tpl; (e = next_tag(e, tend, L"environmentVariable")); e++) {
        wchar_t *vn = attr(e, L"name"), *vv = attr(e, L"value"), *find = attr(e, L"find"), *repl = attr(e, L"replace");
        if (vn) {
            for (wchar_t *c = vn; *c; c++) *c = towupper(*c);
            if (wcscmp(vn, L"FP_STARTUP_PATH") && wcscmp(vn, L"FP_HTDOCS_FILE")) {
                if (find) {
                    /* FSP treats find as a regular expression; a plain substring covers the configs seen. */
                    wchar_t cur[32768]; DWORD got = GetEnvironmentVariableW(vn, cur, 32768);
                    if (got && got < 32768) {
                        wchar_t *hit = wcsstr(cur, find);
                        if (hit) {
                            wchar_t out[32768]; size_t pre = (size_t)(hit - cur);
                            swprintf(out, 32768, L"%.*ls%ls%ls", (int)pre, cur, repl ? repl : L"", hit + wcslen(find));
                            wchar_t *x = expand(out); SetEnvironmentVariableW(vn, x); free(x);
                        }
                    }
                } else {
                    wchar_t *x = expand(vv ? vv : L""); SetEnvironmentVariableW(vn, x); free(x);
                }
            }
        }
        free(vn); free(vv); free(find); free(repl);
    }
    for (const wchar_t *rs = tpl; (rs = next_tag(rs, tend, L"registryStates")); rs++) {
        wchar_t *bt = attr(rs, L"binaryType");
        REGSAM view = (bt && _wcsicmp(bt, L"SCS_64BIT_BINARY")) ? KEY_WOW64_32KEY : KEY_WOW64_64KEY;
        free(bt);
        const wchar_t *rend = wcsstr(rs, L"</registryStates>"); if (!rend || rend > tend) rend = tend;
        for (const wchar_t *r = rs; (r = next_tag(r, rend, L"registryState")); r++) set_registry(r, view);
    }

    /* The mode: SOFTWARE starts its program; WEB_BROWSER (the default) shows the page in
       Internet Explorer's browser control. */
    const wchar_t *mode = next_tag(tpl, tend, L"mode");
    wchar_t *mname = mode ? attr(mode, L"name") : NULL;
    if (!mode || !mname || _wcsicmp(mname, L"SOFTWARE")) {
        wchar_t *wdir = mode ? attr(mode, L"workingDirectory") : NULL;
        if (wdir && *wdir) { wchar_t *x = expand(wdir); SetCurrentDirectoryW(x); free(x); }
        return web_browser(wargv[1], wargv[2]);
    }
    wchar_t *cmd = attr(mode, L"commandLine"), *wd = attr(mode, L"workingDirectory"), *hide = attr(mode, L"hideWindow");
    if (!cmd) { say(L"template %ls has no command line", wargv[1]); return 1; }
    wchar_t *xcmd = expand(cmd);
    size_t cst[64]; int cn = token_starts(xcmd, cst, 64);
    int pargc; wchar_t **pargv = CommandLineToArgvW(xcmd, &pargc);
    if (!pargv || pargc < 1 || cn < 1) { say(L"bad command line: %ls", xcmd); return 1; }
    wchar_t full[MAX_PATH * 2]; GetFullPathNameW(pargv[0], MAX_PATH * 2, full, NULL);
    const wchar_t *tail = cn > 1 ? xcmd + cst[1] : L"";
    size_t ln = wcslen(full) + wcslen(tail) + 8;
    wchar_t *line = malloc(ln * sizeof(wchar_t));
    swprintf(line, ln, L"\"%ls\" %ls", full, tail);
    wchar_t dir[MAX_PATH * 2];
    if (wd && *wd) { wchar_t *x = expand(wd); wcsncpy(dir, x, MAX_PATH * 2 - 1); dir[MAX_PATH * 2 - 1] = 0; free(x); }
    else { wcscpy(dir, full); wchar_t *l = wcsrchr(dir, L'\\'); if (l) *l = 0; }

    STARTUPINFOW si = { sizeof si }; PROCESS_INFORMATION pi;
    DWORD flags = 0;
    if (hide && !_wcsicmp(hide, L"true")) { si.dwFlags = STARTF_USESHOWWINDOW; si.wShowWindow = SW_HIDE; flags |= CREATE_NO_WINDOW; }
    /* The program's console output goes where ours does (ARMFlash's log), for Java's errors. */
    si.dwFlags |= STARTF_USESTDHANDLES;
    si.hStdInput = GetStdHandle(STD_INPUT_HANDLE); si.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE); si.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    say(L"%ls: %ls (in %ls)", wargv[1], line, dir);
    if (!CreateProcessW(full, line, NULL, NULL, TRUE, flags, NULL, dir, &si, &pi)) {
        say(L"could not start %ls (error %lu)", full, GetLastError());
        return 1;
    }
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 0; GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
    say(L"%ls ended (exit code %lu)", wargv[1], code);
    return (int)code;
}
