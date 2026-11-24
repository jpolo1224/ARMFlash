/*
 * ARMFlash's ALSA output for Windows players: an ALSA I/O plugin ("type android_aserver", the
 * name the base system's alsa config already uses) that streams PCM to ARMFlash over a Unix
 * socket, where it plays through Android's AudioTrack (win/AudioServer.kt).
 *
 * Protocol (little-endian), one request = code (1 byte) + length (4 bytes) + body:
 *   1 START, 2 STOP, 3 PAUSE, 0 CLOSE, 6 DRAIN: no body
 *   4 PREPARE: channels (1), format (1: 0 U8, 1 S16_LE, 2 S32_LE, 3 FLOAT_LE), rate (4),
 *              buffer size in frames (4)
 *   5 WRITE:   PCM bytes
 * Replies (server to plugin), 4 bytes each, little-endian:
 *   after a WRITE, its byte count, once AudioTrack holds those bytes;
 *   after a PREPARE, 0xFFFFFFFF.
 * The position ALSA sees is the bytes AudioTrack has taken, so Wine fills its buffer at the
 * rate sound really plays. It is a running count (SND_PCM_IOPLUG_FLAG_BOUNDARY_WA): reported
 * modulo the buffer size, a write of exactly one buffer (Wine's usual write) read back as no
 * progress, ALSA saw a full buffer forever and Wine never wrote again, so games had no sound.
 * The socket is the abstract-namespace name in ARMFLASH_AUDIO (default "armflash.audio").
 *
 * Build: zig cc -target aarch64-linux-gnu.2.36 -shared -fPIC -DPIC -O2 -I<alsa headers> \
 *        -o libasound_module_pcm_android_aserver.so pcm_android_aserver.c -L<rootfs usr/lib> -lasound
 */
#include <alsa/asoundlib.h>
#include <alsa/pcm_external.h>
#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define PREPARED_REPLY 0xFFFFFFFFu

typedef struct {
    snd_pcm_ioplug_t io;
    int fd;
    /* Frames sent since PREPARE, and bytes AudioTrack has taken (the server's replies). */
    uint64_t sent_frames;
    uint64_t taken_bytes;
    unsigned frame_bytes;
    snd_pcm_uframes_t boundary;
    /* A reply split across reads. */
    uint8_t part[4];
    int part_len;
} aserver_t;

static int send_all(int fd, const void *buf, size_t n) {
    const char *p = buf;
    while (n > 0) {
        ssize_t w = send(fd, p, n, MSG_NOSIGNAL);
        if (w <= 0) return -1;
        p += w; n -= (size_t)w;
    }
    return 0;
}

static int request(aserver_t *a, uint8_t code, const void *body, uint32_t len) {
    uint8_t head[5] = { code, (uint8_t)len, (uint8_t)(len >> 8), (uint8_t)(len >> 16), (uint8_t)(len >> 24) };
    if (a->fd < 0 || send_all(a->fd, head, 5) < 0) return -EIO;
    if (len && send_all(a->fd, body, len) < 0) return -EIO;
    return 0;
}

static int connect_server(void) {
    const char *name = getenv("ARMFLASH_AUDIO");
    if (!name || !*name) name = "armflash.audio";
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    size_t n = strlen(name);
    if (n > sizeof(sa.sun_path) - 2) n = sizeof(sa.sun_path) - 2;
    memcpy(sa.sun_path + 1, name, n); /* abstract namespace: leading NUL */
    if (connect(fd, (struct sockaddr *)&sa, (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n)) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int as_start(snd_pcm_ioplug_t *io) { return request(io->private_data, 1, NULL, 0); }
static int as_stop(snd_pcm_ioplug_t *io) { return request(io->private_data, 2, NULL, 0); }
static int as_drain(snd_pcm_ioplug_t *io) { return request(io->private_data, 6, NULL, 0); }

static int as_pause(snd_pcm_ioplug_t *io, int enable) {
    return request(io->private_data, enable ? 3 : 1, NULL, 0);
}

/*
 * Take the server's replies off the socket. Without [until_prepared], only what has already
 * arrived; with it, until the PREPARE reply (giving up after two quiet seconds), which also
 * drops the replies to writes from before the PREPARE. -EIO when the server has gone.
 */
static int read_replies(aserver_t *a, int until_prepared) {
    for (;;) {
        if (until_prepared) {
            struct pollfd p = { .fd = a->fd, .events = POLLIN };
            int r = poll(&p, 1, 2000);
            if (r == 0) return 0;
            if (r < 0) { if (errno == EINTR) continue; return -EIO; }
        }
        uint8_t buf[256];
        ssize_t n = recv(a->fd, buf, sizeof buf, MSG_DONTWAIT);
        if (n == 0) return -EIO;
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) { if (until_prepared) continue; return 0; }
            return -EIO;
        }
        int prepared = 0;
        for (ssize_t i = 0; i < n; i++) {
            a->part[a->part_len++] = buf[i];
            if (a->part_len < 4) continue;
            a->part_len = 0;
            uint32_t v = (uint32_t)a->part[0] | (uint32_t)a->part[1] << 8 | (uint32_t)a->part[2] << 16 | (uint32_t)a->part[3] << 24;
            if (v == PREPARED_REPLY) { a->taken_bytes = 0; prepared = 1; }
            else a->taken_bytes += v;
        }
        if (until_prepared && prepared) return 0;
    }
}

static snd_pcm_sframes_t as_pointer(snd_pcm_ioplug_t *io) {
    aserver_t *a = io->private_data;
    if (a->fd < 0 || read_replies(a, 0) < 0) return -EIO;
    uint64_t frames = a->frame_bytes ? a->taken_bytes / a->frame_bytes : 0;
    /* Never ahead of the application: after a rewind (Wine's Stop), frames already sent play
     * anyway and count again only once the application has written past them. */
    if (frames > io->appl_ptr) frames = io->appl_ptr;
    return (snd_pcm_sframes_t)(a->boundary ? frames % a->boundary : frames);
}

static snd_pcm_sframes_t as_transfer(snd_pcm_ioplug_t *io, const snd_pcm_channel_area_t *areas,
                                     snd_pcm_uframes_t offset, snd_pcm_uframes_t size) {
    aserver_t *a = io->private_data;
    /* Frames a rewind took back were sent already: skip the part of this write that covers
     * them, so the server's count and ALSA's position stay the same thing. */
    uint64_t start = io->appl_ptr;
    snd_pcm_uframes_t skip = 0;
    if (a->sent_frames > start) {
        uint64_t covered = a->sent_frames - start;
        skip = covered < size ? (snd_pcm_uframes_t)covered : size;
    }
    if (skip < size) {
        const char *buf = (const char *)areas->addr + (areas->first + areas->step * (offset + skip)) / 8;
        size_t bytes = snd_pcm_frames_to_bytes(io->pcm, size - skip);
        if (request(a, 5, buf, (uint32_t)bytes) < 0) return -EIO;
    }
    if (start + size > a->sent_frames) a->sent_frames = start + size;
    return (snd_pcm_sframes_t)size;
}

static int as_prepare(snd_pcm_ioplug_t *io) {
    aserver_t *a = io->private_data;
    snd_pcm_sw_params_t *sw;
    snd_pcm_sw_params_alloca(&sw);
    a->boundary = 0;
    if (snd_pcm_sw_params_current(io->pcm, sw) == 0) snd_pcm_sw_params_get_boundary(sw, &a->boundary);
    a->frame_bytes = (unsigned)snd_pcm_frames_to_bytes(io->pcm, 1);
    a->sent_frames = 0;
    uint8_t fmt;
    switch (io->format) {
    case SND_PCM_FORMAT_U8: fmt = 0; break;
    case SND_PCM_FORMAT_S16_LE: fmt = 1; break;
    case SND_PCM_FORMAT_S32_LE: fmt = 2; break;
    case SND_PCM_FORMAT_FLOAT_LE: fmt = 3; break;
    default: return -EINVAL;
    }
    uint8_t body[10];
    uint32_t rate = io->rate, frames = (uint32_t)io->buffer_size;
    body[0] = (uint8_t)io->channels;
    body[1] = fmt;
    memcpy(body + 2, &rate, 4);
    memcpy(body + 6, &frames, 4);
    int err = request(a, 4, body, sizeof body);
    if (err < 0) return err;
    /* The count restarts at the server's PREPARE reply. */
    return read_replies(a, 1);
}

static int as_close(snd_pcm_ioplug_t *io) {
    aserver_t *a = io->private_data;
    request(a, 0, NULL, 0);
    if (a->fd >= 0) close(a->fd);
    free(a);
    return 0;
}

static const snd_pcm_ioplug_callback_t callbacks = {
    .start = as_start,
    .stop = as_stop,
    .pointer = as_pointer,
    .transfer = as_transfer,
    .prepare = as_prepare,
    .drain = as_drain,
    .pause = as_pause,
    .close = as_close,
};

static int constraints(snd_pcm_ioplug_t *io) {
    static const unsigned int access[] = { SND_PCM_ACCESS_RW_INTERLEAVED };
    static const unsigned int formats[] = { SND_PCM_FORMAT_S16_LE, SND_PCM_FORMAT_FLOAT_LE, SND_PCM_FORMAT_S32_LE, SND_PCM_FORMAT_U8 };
    int err;
    if ((err = snd_pcm_ioplug_set_param_list(io, SND_PCM_IOPLUG_HW_ACCESS, 1, access)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_list(io, SND_PCM_IOPLUG_HW_FORMAT, 4, formats)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_CHANNELS, 1, 2)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_RATE, 8000, 48000)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_PERIOD_BYTES, 256, 64 * 1024)) < 0) return err;
    if ((err = snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_BUFFER_BYTES, 1024, 512 * 1024)) < 0) return err;
    return snd_pcm_ioplug_set_param_minmax(io, SND_PCM_IOPLUG_HW_PERIODS, 2, 64);
}

SND_PCM_PLUGIN_DEFINE_FUNC(android_aserver) {
    (void)root; (void)conf;
    if (stream != SND_PCM_STREAM_PLAYBACK) return -ENOTSUP;
    aserver_t *a = calloc(1, sizeof *a);
    if (!a) return -ENOMEM;
    a->fd = connect_server();
    if (a->fd < 0) { free(a); return -ECONNREFUSED; }
    a->io.version = SND_PCM_IOPLUG_VERSION;
    a->io.name = "ARMFlash audio";
    a->io.flags = SND_PCM_IOPLUG_FLAG_BOUNDARY_WA;
    a->io.callback = &callbacks;
    a->io.private_data = a;
    int err = snd_pcm_ioplug_create(&a->io, name, stream, mode);
    if (err < 0) { close(a->fd); free(a); return err; }
    if ((err = constraints(&a->io)) < 0) { snd_pcm_ioplug_delete(&a->io); return err; }
    *pcmp = a->io.pcm;
    return 0;
}

SND_PCM_PLUGIN_SYMBOL(android_aserver);
