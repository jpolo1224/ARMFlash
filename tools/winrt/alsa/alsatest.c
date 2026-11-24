/*
 * alsatest: drives ARMFlash's ALSA plugin the way Wine 10.10's winealsa does, against a
 * stand-in for AudioServer that consumes at real playback speed (an AudioTrack with an 80 ms
 * buffer), and reports how much audio got through.
 *
 * Wine: snd_pcm_open(NONBLOCK), RW interleaved, period 10 ms, 4 periods, start_threshold 1,
 * stop_threshold = buffer; on start one writei of min(avail, held); then every 10 ms writei of
 * min(avail, held) frames. At 1.5 s a Stop-style rewind.
 *
 * usage: alsatest <seconds> <stand-in replies: 0 no | 1 yes> [server socket name]
 * With a socket name, it talks to that server instead of the stand-in: ARMFlash's own
 * AudioServer, run from the APK with app_process and a three-line Main that constructs
 * com.armsx.flashpoint.win.AudioServer(name), sleeps, then System.exit(0)s.
 *
 * Build (glibc 2.39, the runtime's): zig cc -target aarch64-linux-gnu.2.39 -O2 -I<alsa headers>
 *        -o alsatest alsatest.c -L<runtime usr/lib> -lasound -lpthread -lm
 * Run on a device with the runtime installed (as root), the plugin under test in <dir>:
 *   ALSA_PLUGIN_DIR=<dir> <APK lib dir>/libldlinux.so --library-path <data>/r/usr/lib ./alsatest 5 1
 * A good plugin writes about as many seconds of audio as have passed (plus the buffers), with
 * max avail never above the buffer. Before 11-24 it stopped at 0.04 s: the full-buffer stall.
 */
#include <alsa/asoundlib.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define RATE 48000
#define CH 2
static int replies = 1;
static volatile double underrun_s = 0;
static volatile uint64_t consumed_total = 0;

static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }

static int read_all(int fd, void *b, size_t n) {
    char *p = b;
    while (n) { ssize_t r = recv(fd, p, n, 0); if (r <= 0) return -1; p += r; n -= r; }
    return 0;
}

/* AudioServer stand-in: one client, 80 ms buffer drained at RATE. */
static void *server(void *arg) {
    int ls = *(int *)arg, fd;
    while ((fd = accept(ls, NULL, NULL)) >= 0) {
        double level = 0, last = now();
        int frame_bytes = 8, playing = 0;
        const double cap = RATE * 0.080;
        uint8_t head[5];
        static uint8_t buf[1 << 20];
        while (read_all(fd, head, 5) == 0) {
            uint32_t len = head[1] | head[2] << 8 | head[3] << 16 | (uint32_t)head[4] << 24;
            if (len && read_all(fd, buf, len) < 0) break;
            double t = now();
            if (playing) { level -= (t - last) * RATE; if (level < 0) { underrun_s += -level / RATE; level = 0; } }
            last = t;
            if (head[0] == 4) { /* PREPARE */
                frame_bytes = buf[0] * (buf[1] == 0 ? 1 : buf[1] == 1 ? 2 : 4);
                level = 0; playing = 0;
                uint32_t m = 0xFFFFFFFFu; if (replies) send(fd, &m, 4, MSG_NOSIGNAL);
            } else if (head[0] == 5) { /* WRITE: block while the buffer is full, like WRITE_BLOCKING */
                double n = (double)len / frame_bytes;
                playing = 1;
                while (level + n > cap) {
                    usleep(1000);
                    double t2 = now(); level -= (t2 - last) * RATE; last = t2;
                    if (level < 0) level = 0;
                }
                level += n; consumed_total += (uint64_t)n;
                if (replies) send(fd, &len, 4, MSG_NOSIGNAL);
            } else if (head[0] == 0) break;
        }
        close(fd);
    }
    return NULL;
}

int main(int argc, char **argv) {
    double secs = argc > 1 ? atof(argv[1]) : 3;
    replies = argc > 2 ? atoi(argv[2]) : 1;
    /* argv[3]: an existing server's socket name (ARMFlash's own AudioServer) instead of the stand-in. */
    const char *name = argc > 3 ? argv[3] : "armflash.audio.alsatest";
    if (argc <= 3) {
        int ls = socket(AF_UNIX, SOCK_STREAM, 0);
        struct sockaddr_un sa = { .sun_family = AF_UNIX };
        memcpy(sa.sun_path + 1, name, strlen(name));
        if (bind(ls, (struct sockaddr *)&sa, offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name)) < 0 || listen(ls, 4) < 0) { perror("bind"); return 1; }
        static int lsfd; lsfd = ls;
        pthread_t th; pthread_create(&th, NULL, server, &lsfd);
    }
    setenv("ARMFLASH_AUDIO", name, 1);

    snd_pcm_t *pcm; int err;
    if ((err = snd_pcm_open(&pcm, "default", SND_PCM_STREAM_PLAYBACK, SND_PCM_NONBLOCK)) < 0) { printf("open: %s\n", snd_strerror(err)); return 1; }
    snd_pcm_hw_params_t *hw; snd_pcm_hw_params_alloca(&hw);
    snd_pcm_hw_params_any(pcm, hw);
    snd_pcm_hw_params_set_access(pcm, hw, SND_PCM_ACCESS_RW_INTERLEAVED);
    snd_pcm_hw_params_set_format(pcm, hw, SND_PCM_FORMAT_FLOAT_LE);
    snd_pcm_hw_params_set_channels(pcm, hw, CH);
    unsigned rate = RATE; snd_pcm_hw_params_set_rate_near(pcm, hw, &rate, NULL);
    unsigned period_us = 10000; snd_pcm_hw_params_set_period_time_near(pcm, hw, &period_us, NULL);
    unsigned periods = 4; snd_pcm_hw_params_set_periods_near(pcm, hw, &periods, NULL);
    if ((err = snd_pcm_hw_params(pcm, hw)) < 0) { printf("hw_params: %s\n", snd_strerror(err)); return 1; }
    snd_pcm_uframes_t period, bufsize;
    snd_pcm_hw_params_get_period_size(hw, &period, NULL);
    snd_pcm_hw_params_get_buffer_size(hw, &bufsize);
    snd_pcm_sw_params_t *sw; snd_pcm_sw_params_alloca(&sw);
    snd_pcm_sw_params_current(pcm, sw);
    snd_pcm_sw_params_set_start_threshold(pcm, sw, 1);
    snd_pcm_sw_params_set_stop_threshold(pcm, sw, bufsize);
    snd_pcm_sw_params(pcm, sw);
    snd_pcm_prepare(pcm);
    printf("period %lu, buffer %lu frames\n", period, bufsize);

    /* The game's audio: always plenty queued (as winmm keeps mmdevapi's 1 s buffer full). */
    static float data[RATE * CH];
    for (int i = 0; i < RATE; i++) data[i * 2] = data[i * 2 + 1] = 0.0f; /* silence: pacing is what is measured */
    uint64_t written = 0, eagain = 0, maxavail = 0;
    double t0 = now(), next = t0, rewound_at = 0, last_print = t0;
    int rewound = 0;
    while (now() - t0 < secs) {
        snd_pcm_sframes_t avail = snd_pcm_avail_update(pcm);
        if (avail < 0) { printf("avail_update: %s at %.2f s\n", snd_strerror((int)avail), now() - t0); snd_pcm_prepare(pcm); continue; }
        if ((uint64_t)avail > maxavail) maxavail = avail;
        if (!rewound && now() - t0 > 1.5) {
            snd_pcm_sframes_t r = snd_pcm_rewindable(pcm);
            snd_pcm_sframes_t back = r > 0 ? snd_pcm_rewind(pcm, r) : 0;
            if (back > 0) written -= back;
            rewound = 1; rewound_at = now() - t0;
            printf("rewind: rewindable %ld, rewound %ld at %.2f s\n", (long)r, (long)back, rewound_at);
        }
        if (avail > 0) {
            snd_pcm_uframes_t n = (snd_pcm_uframes_t)avail > bufsize ? bufsize : (snd_pcm_uframes_t)avail;
            snd_pcm_sframes_t w = snd_pcm_writei(pcm, data + (written % (RATE - bufsize)) * CH, n);
            if (w == -EAGAIN) eagain++;
            else if (w < 0) { printf("writei: %s\n", snd_strerror((int)w)); snd_pcm_recover(pcm, (int)w, 1); }
            else written += w;
        }
        if (now() - last_print >= 0.5) {
            snd_pcm_sframes_t delay = 0; snd_pcm_delay(pcm, &delay);
            printf("t %.1f s: written %llu frames (%.2f s of audio), consumed %.2f s, avail %ld, delay %ld, state %d\n",
                   now() - t0, (unsigned long long)written, written / (double)RATE, consumed_total / (double)RATE, (long)avail, (long)delay, snd_pcm_state(pcm));
            last_print = now();
        }
        next += 0.010;
        double d = next - now(); if (d > 0) usleep((useconds_t)(d * 1e6));
    }
    double el = now() - t0;
    printf("RESULT: %.2f s elapsed, %.2f s of audio written, %.2f s consumed, underrun %.3f s, max avail %llu (buffer %lu), EAGAIN %llu\n",
           el, written / (double)RATE, consumed_total / (double)RATE, underrun_s, (unsigned long long)maxavail, bufsize, (unsigned long long)eagain);
    snd_pcm_close(pcm);
    return 0;
}
