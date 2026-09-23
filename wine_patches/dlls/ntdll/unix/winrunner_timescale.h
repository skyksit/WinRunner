/*
 * WinRunner game speed - guest half.
 *
 * Wine games are not emulated: nothing in the stack owns a core loop that could be run more or
 * fewer times the way an emulator frontend does it. The only thing that changes a game's speed is
 * the clock it reads, so this scales the clock ntdll hands out and shortens the waits that go with
 * it. Between them that covers QueryPerformanceCounter, timeGetTime (winmm builds it on QPC),
 * Sleep, and every wait timeout.
 *
 * The host (com.winlator.speed.Timescale) publishes the mapping into a small file named by
 * WINRUNNER_TIMESCALE_FILE:
 *
 *     virtual = base_virtual + (real - base_real) * scale
 *
 * The host rebases it on every change, so virtual time stays monotonic and continuous across a
 * toggle - a backwards jump there breaks practically every game. It also means each guest process
 * only ever reads; none of them has to agree on when a change happened.
 *
 * Publication is double-buffered instead of locked: the host fills the inactive slot and then flips
 * a single aligned 32-bit field, which is atomic on arm64. A reader that samples 'active' can only
 * be torn by two further updates, and updates come from a person tapping a button while these reads
 * happen every time the game asks for the time.
 *
 * Header-only, allocation-free and self-initialising, so it is safe to call from anywhere in ntdll's
 * unix side, including before the loader is done, and it needs no init hook. With no file or an
 * unreadable one, every function returns exactly what the unpatched code would have.
 *
 * ⚠ Once the file is present the virtual clock is used even at scale 1.0. That is deliberate: the
 * stock monotonic_counter() prefers CLOCK_MONOTONIC_RAW while this mapping is anchored on
 * CLOCK_MONOTONIC, and switching between the two mid-run would step QueryPerformanceCounter - the
 * one thing the mapping exists to prevent.
 *
 * This file belongs to the skyksit/DGwine build; see wine_patches/README.md for the call sites.
 */

#ifndef __WINRUNNER_TIMESCALE_H
#define __WINRUNNER_TIMESCALE_H

#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#define WINRUNNER_TIMESCALE_MAGIC   0x57525453  /* 'WRTS' */
#define WINRUNNER_TIMESCALE_VERSION 1

/* Wine's clock unit: 100ns ticks. */
#define WINRUNNER_TIMESCALE_TICK 100

struct winrunner_timescale_slot
{
    int64_t base_real;      /* CLOCK_MONOTONIC nanoseconds */
    int64_t base_virtual;   /* nanoseconds */
    double  scale;
};

struct winrunner_timescale_map
{
    uint32_t magic;
    uint32_t version;
    uint32_t active;
    uint32_t pad;
    struct winrunner_timescale_slot slots[2];
};

/* The host writes these offsets literally (com.winlator.speed.Timescale); keep the two in step. */
#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert( sizeof(struct winrunner_timescale_slot) == 24, "timescale slot layout changed" );
_Static_assert( offsetof(struct winrunner_timescale_map, active) == 8, "timescale layout changed" );
_Static_assert( offsetof(struct winrunner_timescale_map, slots) == 16, "timescale layout changed" );
_Static_assert( sizeof(struct winrunner_timescale_map) == 64, "timescale layout changed" );
#endif

/*
 * Mapped lazily rather than from an init hook, so this header needs no call site of its own and no
 * Makefile.in change: every translation unit that includes it gets its own pointer, and a couple of
 * read-only mappings of a 64-byte file cost nothing. Two threads racing here at worst leak one such
 * mapping, once.
 */
static inline const volatile struct winrunner_timescale_map *winrunner_timescale_get_map(void)
{
    static const volatile struct winrunner_timescale_map *map;
    static int tried;
    const char *path;
    void *addr;
    int fd;

    if (tried) return map;
    tried = 1;

    if (!(path = getenv( "WINRUNNER_TIMESCALE_FILE" )) || !path[0]) return NULL;
    if ((fd = open( path, O_RDONLY | O_CLOEXEC )) == -1) return NULL;

    addr = mmap( NULL, sizeof(struct winrunner_timescale_map), PROT_READ, MAP_SHARED, fd, 0 );
    close( fd );
    if (addr == MAP_FAILED) return NULL;

    map = addr;
    return map;
}

/* Returns 0 when there is no usable mapping; the caller then keeps the stock behaviour. */
static inline int winrunner_timescale_read( struct winrunner_timescale_slot *slot )
{
    const volatile struct winrunner_timescale_map *map = winrunner_timescale_get_map();
    unsigned int active;

    if (!map) return 0;
    if (map->magic != WINRUNNER_TIMESCALE_MAGIC || map->version != WINRUNNER_TIMESCALE_VERSION) return 0;

    active = map->active & 1;
    slot->base_real = map->slots[active].base_real;
    slot->base_virtual = map->slots[active].base_virtual;
    slot->scale = map->slots[active].scale;

    return slot->scale > 0.0;   /* also rejects NaN */
}

/**
 * The unscaled monotonic clock, in 100ns ticks, anchoring deadlines that leave for wineserver.
 *
 * ⚠ This must select its clock exactly the way wineserver's own monotonic_counter() does
 * (server/request.c) - CLOCK_MONOTONIC_RAW first - because the server compares the deadline we
 * send against that counter. CLOCK_MONOTONIC and CLOCK_MONOTONIC_RAW drift apart by NTP slew, so
 * anchoring on the wrong one skews every wait. That it also matches the stock ntdll counter is why
 * the caller needs no branch: with the feature off this returns exactly the stock value.
 */
static inline int64_t winrunner_timescale_real_100ns(void)
{
    struct timespec ts;

#ifdef CLOCK_MONOTONIC_RAW
    if (!clock_gettime( CLOCK_MONOTONIC_RAW, &ts ))
        return (int64_t)ts.tv_sec * (1000000000 / WINRUNNER_TIMESCALE_TICK)
               + ts.tv_nsec / WINRUNNER_TIMESCALE_TICK;
#endif
    clock_gettime( CLOCK_MONOTONIC, &ts );
    return (int64_t)ts.tv_sec * (1000000000 / WINRUNNER_TIMESCALE_TICK)
           + ts.tv_nsec / WINRUNNER_TIMESCALE_TICK;
}

/**
 * The monotonic clock as the game should see it, in 100ns ticks.
 *
 * @return 0 when the feature is not in use, leaving the caller on its stock clock.
 */
static inline int winrunner_timescale_monotonic_100ns( int64_t *out )
{
    struct winrunner_timescale_slot slot;
    struct timespec ts;
    int64_t real;

    if (!winrunner_timescale_read( &slot )) return 0;

    clock_gettime( CLOCK_MONOTONIC, &ts );
    real = (int64_t)ts.tv_sec * 1000000000 + ts.tv_nsec;
    *out = (slot.base_virtual + (int64_t)((double)(real - slot.base_real) * slot.scale))
           / WINRUNNER_TIMESCALE_TICK;
    return 1;
}

/**
 * Converts a duration the game asked for into the real one to wait.
 *
 * A relative timeout is game time: at 2x a Sleep(1000) has to return in half a real second, or the
 * game stalls instead of speeding up. Unit-agnostic - the caller's units come back out.
 */
static inline int64_t winrunner_timescale_scale_duration( int64_t duration )
{
    struct winrunner_timescale_slot slot;

    if (duration <= 0) return duration;
    if (!winrunner_timescale_read( &slot )) return duration;
    return (int64_t)((double)duration / slot.scale);
}

#endif /* __WINRUNNER_TIMESCALE_H */
