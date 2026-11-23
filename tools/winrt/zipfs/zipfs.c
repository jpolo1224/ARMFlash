/*
 * libarmflash_zipfs.so: files inside a game's zip, for PHP, without unpacking them.
 *
 * Preloaded into php-cgi by ARMFlash's game server (fp-proxy's php.rs) when a game keeps a big
 * data file next to its scripts, such as Happy Wheels' 18 GB level database. Each such file is
 * an empty, read-only placeholder on disk; this library gives it its real size (fstat) and its
 * bytes (read, pread, lseek), asking the game server for them over a Unix socket (zipseek.rs).
 * Files are recognised by device and inode, so any path or folder that reaches them works.
 * Everything else goes to the C library untouched.
 *
 *   ARMFLASH_VFILES   the manifest: one "id<TAB>size<TAB>path" line per file
 *   ARMFLASH_ZIPREAD  the game server's socket
 *
 * Build: python3 -m ziglang cc -target aarch64-linux-gnu.2.36 -shared -fPIC -O2 \
 *        -o libarmflash_zipfs.so zipfs.c
 */
#define _GNU_SOURCE
#undef _FORTIFY_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

typedef struct { dev_t dev; ino_t ino; uint32_t id; uint64_t size; } vfile;

static vfile *files;
static int nfiles;
static pthread_once_t once = PTHREAD_ONCE_INIT;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int sock = -1;

#define MAXFD 4096
static int vf_of[MAXFD];       /* fd -> index in files, plus one (0: not ours) */
static uint64_t pos_of[MAXFD]; /* fd -> file position, for read and lseek */

static int (*real_open)(const char *, int, ...);
static int (*real_open64)(const char *, int, ...);
static int (*real_openat)(int, const char *, int, ...);
static int (*real_openat64)(int, const char *, int, ...);
static int (*real___open_2)(const char *, int);
static int (*real___open64_2)(const char *, int);
static int (*real___openat_2)(int, const char *, int);
static int (*real___openat64_2)(int, const char *, int);
static int (*real_close)(int);
static int (*real_dup)(int);
static int (*real_dup2)(int, int);
static int (*real_dup3)(int, int, int);
static ssize_t (*real_read)(int, void *, size_t);
static ssize_t (*real___read_chk)(int, void *, size_t, size_t);
static ssize_t (*real_pread)(int, void *, size_t, off_t);
static ssize_t (*real_pread64)(int, void *, size_t, off64_t);
static ssize_t (*real___pread_chk)(int, void *, size_t, off_t, size_t);
static ssize_t (*real___pread64_chk)(int, void *, size_t, off64_t, size_t);
static off_t (*real_lseek)(int, off_t, int);
static off64_t (*real_lseek64)(int, off64_t, int);
static void *(*real_mmap)(void *, size_t, int, int, int, off_t);
static void *(*real_mmap64)(void *, size_t, int, int, int, off64_t);
static int (*real_fstat)(int, struct stat *);
static int (*real_fstat64)(int, struct stat64 *);
static int (*real___fxstat)(int, int, struct stat *);
static int (*real___fxstat64)(int, int, struct stat64 *);

/* glibc 2.33 and later keep __fxstat and __fxstat64 only for programs built before then, such as
 * Debian 11's PHP and SQLite: dlsym does not return such old versions, dlvsym does. */
static void *oldsym(const char *name) {
    void *p = dlvsym(RTLD_NEXT, name, "GLIBC_2.17");
    return p ? p : dlsym(RTLD_NEXT, name);
}

/* fstat without this library's own size change. */
static int raw_fstat(int fd, struct stat *st) {
    if (real_fstat) return real_fstat(fd, st);
    if (real___fxstat) return real___fxstat(0, fd, st);
    errno = ENOSYS;
    return -1;
}

static void init(void) {
    real_open = dlsym(RTLD_NEXT, "open");
    real_open64 = dlsym(RTLD_NEXT, "open64");
    real_openat = dlsym(RTLD_NEXT, "openat");
    real_openat64 = dlsym(RTLD_NEXT, "openat64");
    real___open_2 = dlsym(RTLD_NEXT, "__open_2");
    real___open64_2 = dlsym(RTLD_NEXT, "__open64_2");
    real___openat_2 = dlsym(RTLD_NEXT, "__openat_2");
    real___openat64_2 = dlsym(RTLD_NEXT, "__openat64_2");
    real_close = dlsym(RTLD_NEXT, "close");
    real_dup = dlsym(RTLD_NEXT, "dup");
    real_dup2 = dlsym(RTLD_NEXT, "dup2");
    real_dup3 = dlsym(RTLD_NEXT, "dup3");
    real_read = dlsym(RTLD_NEXT, "read");
    real___read_chk = dlsym(RTLD_NEXT, "__read_chk");
    real_pread = dlsym(RTLD_NEXT, "pread");
    real_pread64 = dlsym(RTLD_NEXT, "pread64");
    real___pread_chk = dlsym(RTLD_NEXT, "__pread_chk");
    real___pread64_chk = dlsym(RTLD_NEXT, "__pread64_chk");
    real_lseek = dlsym(RTLD_NEXT, "lseek");
    real_lseek64 = dlsym(RTLD_NEXT, "lseek64");
    real_mmap = dlsym(RTLD_NEXT, "mmap");
    real_mmap64 = dlsym(RTLD_NEXT, "mmap64");
    real_fstat = dlsym(RTLD_NEXT, "fstat");
    real_fstat64 = dlsym(RTLD_NEXT, "fstat64");
    real___fxstat = oldsym("__fxstat");
    real___fxstat64 = oldsym("__fxstat64");
    const char *m = getenv("ARMFLASH_VFILES");
    FILE *f = m ? fopen(m, "re") : NULL;
    if (!f) return;
    char line[PATH_MAX + 64];
    while (fgets(line, sizeof line, f)) {
        char *a = strchr(line, '\t'); if (!a) continue;
        char *b = strchr(a + 1, '\t'); if (!b) continue;
        char *nl = strchr(b + 1, '\n'); if (nl) *nl = 0;
        struct stat st;
        if (stat(b + 1, &st)) continue;
        vfile *grown = realloc(files, (size_t)(nfiles + 1) * sizeof *files);
        if (!grown) break;
        files = grown;
        files[nfiles].dev = st.st_dev;
        files[nfiles].ino = st.st_ino;
        files[nfiles].id = (uint32_t)strtoul(line, NULL, 10);
        files[nfiles].size = strtoull(a + 1, NULL, 10);
        nfiles++;
    }
    fclose(f);
}

#define READY() pthread_once(&once, init)

/* A newly opened fd: remember it if it is one of our files (any fd number reused is reset). */
static int opened(int fd) {
    if (fd < 0 || fd >= MAXFD) return fd;
    vf_of[fd] = 0;
    pos_of[fd] = 0;
    struct stat st;
    if (nfiles && !raw_fstat(fd, &st))
        for (int i = 0; i < nfiles; i++)
            if (files[i].dev == st.st_dev && files[i].ino == st.st_ino) { vf_of[fd] = i + 1; break; }
    return fd;
}

static int ours(int fd) { return fd >= 0 && fd < MAXFD ? vf_of[fd] : 0; }

static int connect_server(void) {
    const char *p = getenv("ARMFLASH_ZIPREAD");
    if (!p) return -1;
    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    snprintf(sa.sun_path, sizeof sa.sun_path, "%s", p);
    if (connect(s, (struct sockaddr *)&sa, sizeof sa) < 0) { real_close(s); return -1; }
    return s;
}

static int all(int s, void *buf, size_t n, int writing) {
    char *p = buf;
    while (n) {
        ssize_t r = writing ? send(s, p, n, MSG_NOSIGNAL) : recv(s, p, n, 0);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return -1;
        p += r;
        n -= (size_t)r;
    }
    return 0;
}

/* Bytes [off, off+n) of file `which` (one-based) straight from the game server. */
static ssize_t server_read(vfile *v, char *buf, size_t n, uint64_t off) {
    static int warned;
    size_t done = 0;
    while (done < n) {
        if (sock < 0) sock = connect_server();
        if (sock < 0) {
            if (!warned++) fprintf(stderr, "zipfs: cannot reach the game server (%s)\n", strerror(errno));
            break;
        }
        uint32_t want = (uint32_t)((n - done) > (4u << 20) ? (4u << 20) : (n - done));
        uint64_t at = off + done;
        unsigned char req[16];
        memcpy(req, &v->id, 4);
        memcpy(req + 4, &want, 4);
        memcpy(req + 8, &at, 8);
        int32_t got;
        if (all(sock, req, 16, 1) || all(sock, &got, 4, 0) || got < 0 || (uint32_t)got > want ||
            (got > 0 && all(sock, buf + done, (size_t)got, 0))) {
            if (!warned++) fprintf(stderr, "zipfs: the game server could not read file %u at %llu (%d)\n",
                                   v->id, (unsigned long long)at, got);
            real_close(sock);
            sock = -1;
            break;
        }
        if (got == 0) break;
        done += (size_t)got;
    }
    return (ssize_t)done;
}

/* Small reads go through a few cached blocks, so a table scan (SQLite reading page after page)
 * takes one round trip per block instead of one per page. */
#define BLOCK (256u << 10)
#define NBLOCKS 8
static struct { vfile *v; uint64_t start; size_t len; char *data; } blocks[NBLOCKS];
static int next_block;

static ssize_t fetch(int which, void *out, size_t n, uint64_t off) {
    vfile *v = &files[which - 1];
    if (off >= v->size) return 0;
    if (n > v->size - off) n = (size_t)(v->size - off);
    char *buf = out;
    size_t done = 0;
    pthread_mutex_lock(&lock);
    if (n >= BLOCK) {
        ssize_t r = server_read(v, buf, n, off);
        done = r > 0 ? (size_t)r : 0;
    } else {
        while (done < n) {
            uint64_t at = off + done, start = at / BLOCK * BLOCK;
            int b = -1;
            for (int i = 0; i < NBLOCKS; i++)
                if (blocks[i].v == v && blocks[i].start == start && blocks[i].len) { b = i; break; }
            if (b < 0) {
                b = next_block;
                next_block = (next_block + 1) % NBLOCKS;
                if (!blocks[b].data && !(blocks[b].data = malloc(BLOCK))) break;
                uint64_t want = v->size - start < BLOCK ? v->size - start : BLOCK;
                ssize_t r = server_read(v, blocks[b].data, (size_t)want, start);
                blocks[b].v = v;
                blocks[b].start = start;
                blocks[b].len = r > 0 ? (size_t)r : 0;
            }
            size_t from = (size_t)(at - start);
            if (from >= blocks[b].len) break;
            size_t take = blocks[b].len - from < n - done ? blocks[b].len - from : n - done;
            memcpy(buf + done, blocks[b].data + from, take);
            done += take;
        }
    }
    pthread_mutex_unlock(&lock);
    if (!done && n) { errno = EIO; return -1; }
    return (ssize_t)done;
}

static mode_t mode_arg(int flags, va_list ap) {
    return (flags & O_CREAT) || (flags & __O_TMPFILE) == __O_TMPFILE ? (mode_t)va_arg(ap, int) : 0;
}

int open(const char *path, int flags, ...) {
    READY();
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    return opened(real_open(path, flags, m));
}

int open64(const char *path, int flags, ...) {
    READY();
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    return opened(real_open64 ? real_open64(path, flags, m) : real_open(path, flags, m));
}

int openat(int dirfd, const char *path, int flags, ...) {
    READY();
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    return opened(real_openat(dirfd, path, flags, m));
}

int openat64(int dirfd, const char *path, int flags, ...) {
    READY();
    va_list ap; va_start(ap, flags); mode_t m = mode_arg(flags, ap); va_end(ap);
    return opened(real_openat64 ? real_openat64(dirfd, path, flags, m) : real_openat(dirfd, path, flags, m));
}

int __open_2(const char *path, int flags) {
    READY();
    return opened(real___open_2 ? real___open_2(path, flags) : real_open(path, flags));
}

int __open64_2(const char *path, int flags) {
    READY();
    return opened(real___open64_2 ? real___open64_2(path, flags) : real_open(path, flags));
}

int __openat_2(int dirfd, const char *path, int flags) {
    READY();
    return opened(real___openat_2 ? real___openat_2(dirfd, path, flags) : real_openat(dirfd, path, flags));
}

int __openat64_2(int dirfd, const char *path, int flags) {
    READY();
    return opened(real___openat64_2 ? real___openat64_2(dirfd, path, flags) : real_openat(dirfd, path, flags));
}

int close(int fd) {
    READY();
    if (fd >= 0 && fd < MAXFD) vf_of[fd] = 0;
    return real_close(fd);
}

/* A copy of an fd is ours when the original is (with its own position, which is enough here). */
static int copied(int from, int to) {
    if (to >= 0 && to < MAXFD && to != from) {
        vf_of[to] = ours(from);
        pos_of[to] = vf_of[to] ? pos_of[from] : 0;
    }
    return to;
}

int dup(int fd) {
    READY();
    return copied(fd, real_dup(fd));
}

int dup2(int fd, int to) {
    READY();
    return copied(fd, real_dup2(fd, to));
}

int dup3(int fd, int to, int flags) {
    READY();
    return copied(fd, real_dup3(fd, to, flags));
}

static ssize_t read_on(int fd, int w, void *buf, size_t n) {
    ssize_t r = fetch(w, buf, n, pos_of[fd]);
    if (r > 0) pos_of[fd] += (uint64_t)r;
    return r;
}

ssize_t read(int fd, void *buf, size_t n) {
    READY();
    int w = ours(fd);
    return w ? read_on(fd, w, buf, n) : real_read(fd, buf, n);
}

ssize_t __read_chk(int fd, void *buf, size_t n, size_t buflen) {
    READY();
    int w = ours(fd);
    return w ? read_on(fd, w, buf, n < buflen ? n : buflen) : real___read_chk(fd, buf, n, buflen);
}

ssize_t pread(int fd, void *buf, size_t n, off_t off) {
    READY();
    int w = ours(fd);
    return w ? fetch(w, buf, n, (uint64_t)off) : real_pread(fd, buf, n, off);
}

ssize_t pread64(int fd, void *buf, size_t n, off64_t off) {
    READY();
    int w = ours(fd);
    return w ? fetch(w, buf, n, (uint64_t)off) : real_pread64(fd, buf, n, off);
}

ssize_t __pread_chk(int fd, void *buf, size_t n, off_t off, size_t buflen) {
    READY();
    int w = ours(fd);
    return w ? fetch(w, buf, n < buflen ? n : buflen, (uint64_t)off) : real___pread_chk(fd, buf, n, off, buflen);
}

ssize_t __pread64_chk(int fd, void *buf, size_t n, off64_t off, size_t buflen) {
    READY();
    int w = ours(fd);
    return w ? fetch(w, buf, n < buflen ? n : buflen, (uint64_t)off) : real___pread64_chk(fd, buf, n, off, buflen);
}

static off64_t seek(int fd, int w, off64_t off, int whence) {
    int64_t base;
    if (whence == SEEK_SET) base = 0;
    else if (whence == SEEK_CUR) base = (int64_t)pos_of[fd];
    else if (whence == SEEK_END) base = (int64_t)files[w - 1].size;
    else { errno = EINVAL; return -1; }
    if (base + off < 0) { errno = EINVAL; return -1; }
    pos_of[fd] = (uint64_t)(base + off);
    return (off64_t)pos_of[fd];
}

off_t lseek(int fd, off_t off, int whence) {
    READY();
    int w = ours(fd);
    return w ? (off_t)seek(fd, w, off, whence) : real_lseek(fd, off, whence);
}

off64_t lseek64(int fd, off64_t off, int whence) {
    READY();
    int w = ours(fd);
    return w ? seek(fd, w, off, whence) : real_lseek64(fd, off, whence);
}

/* The placeholder has no bytes to map: callers (SQLite, PHP's streams) fall back to reading. */
void *mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off) {
    READY();
    if (ours(fd)) { errno = ENODEV; return MAP_FAILED; }
    return real_mmap(addr, len, prot, flags, fd, off);
}

void *mmap64(void *addr, size_t len, int prot, int flags, int fd, off64_t off) {
    READY();
    if (ours(fd)) { errno = ENODEV; return MAP_FAILED; }
    return real_mmap64 ? real_mmap64(addr, len, prot, flags, fd, off) : real_mmap(addr, len, prot, flags, fd, (off_t)off);
}

/* The placeholder's own stat, with the real file's size. */
static void sized(struct stat *st, int w) {
    if (!w) return;
    st->st_size = (off_t)files[w - 1].size;
    st->st_blocks = (blkcnt_t)((files[w - 1].size + 511) / 512);
}

int fstat(int fd, struct stat *st) {
    READY();
    int r = raw_fstat(fd, st);
    if (!r) sized(st, ours(fd));
    return r;
}

/* struct stat and struct stat64 are the same on 64-bit Arm. */
int fstat64(int fd, struct stat64 *st) {
    READY();
    int r = real_fstat64 ? real_fstat64(fd, st) : raw_fstat(fd, (struct stat *)st);
    if (!r) sized((struct stat *)st, ours(fd));
    return r;
}

int __fxstat(int ver, int fd, struct stat *st) {
    READY();
    int r = real___fxstat ? real___fxstat(ver, fd, st) : raw_fstat(fd, st);
    if (!r) sized(st, ours(fd));
    return r;
}

int __fxstat64(int ver, int fd, struct stat64 *st) {
    READY();
    int r = real___fxstat64 ? real___fxstat64(ver, fd, st) : raw_fstat(fd, (struct stat *)st);
    if (!r) sized((struct stat *)st, ours(fd));
    return r;
}
