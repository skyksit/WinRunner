# Wine patches

Source that is dropped into a Wine tree built **elsewhere**, the same way `glibc_patches/` and
`android_alsa/` work. Nothing here is compiled by Gradle.

The build lives in [`skyksit/DGwine`](https://github.com/skyksit/DGwine) — already the place the
shipped `quartz.dll` is built from. Its CI (`build-wine-modules.yml`) checks out Wine at a tag,
applies `patches/*.patch` in order, builds `--enable-archs=i386,x86_64`, and publishes only the
modules the container replaces.

⚠ **The base tree for this patch is not upstream Wine.** It is
[`brunodev85/wine-10.10-custom`](https://github.com/brunodev85/wine-10.10-custom) — "Main Wine
version used in Winlator", the tree the shipped rootfs is built from. See *Why not upstream* below;
DGwine's workflow clones `wine-mirror/wine`, so it needs the source repo made configurable before it
can build this one.

## Game speed — fast forward / slow motion

Fast forward and slow motion for Windows games. Unlike an emulator frontend (dsam3 scales
`retro_run()` call counts, PCSX2 scales its frame-limiter tick budget), nothing on this stack owns a
core loop: the game's x86 code is translated by box64 and paces itself. The only lever is the clock
the game reads, so this scales it and shortens the waits that go with it.

- `dlls/ntdll/unix/winrunner_timescale.h` — the reader. Source of truth; header-only,
  allocation-free, self-initialising. See its comment for the shared-memory protocol. The host half
  is `com.winlator.speed.Timescale`.
- `patches/0003-ntdll-scale-the-game-clock-for-winrunner.patch` — **generated**, the file DGwine
  actually applies. Regenerate after editing the header:

  ```sh
  git clone --depth 1 https://github.com/brunodev85/wine-10.10-custom.git wine
  cp .../winrunner_timescale.h wine/dlls/ntdll/unix/
  # re-apply the five call-site edits, then:
  git -C wine add -A dlls/ntdll/unix && git -C wine diff --cached > 0003-....patch
  ```

  Verified: applies cleanly to `wine-10.10-custom` at `2b9afc3` (2026-09-10).

### What it touches (verified against the wine-10.10 source, not guessed)

| Call site | Why |
|---|---|
| `dlls/ntdll/unix/sync.c` `monotonic_counter()` | The only source of `QueryPerformanceCounter` |
| `dlls/ntdll/unix/sync.c` `NtDelayExecution()` | `Sleep` — a relative timeout is game time, so it shortens |
| `dlls/ntdll/unix/sync.c` `get_absolute_timeout()` | Same, for the futex/kqueue wait path |
| `dlls/ntdll/unix/server.c` `server_wait()` | Wait timeouts. **Must anchor on the unscaled counter** — wineserver measures the deadline with its own `monotonic_counter()` (`server/request.c`), which we do not patch |
| `dlls/ntdll/unix/esync.c` wait setup | esync runs its **own** wait loop and never reaches `server_wait`. Miss this and every esync wait stays at 1x — and esync is on by default (`WINEESYNC=1`) |

### What that covers

| Windows API | Scaled? | How |
|---|---|---|
| `QueryPerformanceCounter` | ✅ | `monotonic_counter()` |
| `timeGetTime` | ✅ | winmm builds it on QPC (`dlls/winmm/time.c`) — nothing extra needed |
| `Sleep`, `SleepEx` | ✅ | `NtDelayExecution` |
| `WaitForSingleObject` & co. timeouts | ✅ | `server_wait` / `get_absolute_timeout` |
| `GetTickCount`, `GetTickCount64` | ❌ | They read `user_shared_data->TickCount` directly (`dlls/kernelbase/sync.c`), which **wineserver** writes (`server/fd.c`). Needs a second patch and shipping `wineserver` too |
| `GetSystemTimeAsFileTime` | ❌ | Deliberate — `NtQuerySystemTime` stays the real wall clock, so save timestamps and licence checks keep working |

### Two things the source corrected

1. **Deadlines are wall-clock or server-clock, never the scaled one.** Wine turns a relative timeout
   into an absolute deadline and then measures it with an unscaled clock, so the fix is to scale the
   *duration* at conversion — not to invert the mapping afterwards. That is why neither wineserver
   nor `esync` needs touching for waits.
2. **`timeGetTime` and `GetTickCount` are not the same story.** The first rides on QPC and comes for
   free; the second lives in shared memory owned by wineserver and does not.

### Risk gate

With no mapping file, every helper returns exactly what the unpatched code returned — the same clock
(`CLOCK_MONOTONIC_RAW` first) and the same arithmetic. ntdll is the most central module in the
prefix, so the regression that matters is not "does 9x work" but **"is 1x still identical"**: test
that first.

Once the file *is* present the virtual clock is used even at scale 1.0, on purpose. The stock counter
prefers `CLOCK_MONOTONIC_RAW` while the mapping is anchored on `CLOCK_MONOTONIC`; switching between
them at a toggle would step QPC, which is the one thing the mapping exists to prevent.

## Why not upstream

`WINEESYNC=1` is set on every session (`XServerDisplayActivity.setupXEnvironment`), and esync is not
an upstream feature — it has never been in any upstream Wine release. Checked:

- upstream `wine-10.10`: no `esync.c`, and `WINEESYNC` appears nowhere in the tree
- upstream `master`: same, and its answer to the problem is `ntsync` (the kernel `/dev/ntsync`
  driver, used straight from `sync.c`), which 10.10 does not have either
- current `wine-staging` (120 patchsets): nothing named for sync or eventfd
- **the shipped container**: `ntdll.so` carries `WINEESYNC`×4 / `esync`×19 and `wineserver`
  `esync`×45 — so the rootfs is definitively an esync build

quartz got away with a cross-version swap because it is a leaf module. ntdll is the syscall boundary:
an upstream-built one would drop esync and would not speak the same protocol as the container's
`wineserver`. So the base has to be Winlator's own tree, `brunodev85/wine-10.10-custom`, which
carries esync (`dlls/ntdll/unix/esync.c`, `server/esync.c`) and matches the container's version.

Its four stock call sites are byte-identical to upstream 10.10; only the include context differs, and
`esync.c` adds the fifth hunk.

## Shipping (open questions)

1. **DGwine must be able to clone a different source repo.** Its workflow hardcodes
   `wine-mirror/wine` and defaults to the `wine-10.11` tag; this patch needs
   `brunodev85/wine-10.10-custom`.
2. **Ship the matched set, and include `wineserver`.** PE `ntdll.dll` (i386 + x86_64) plus unix
   `ntdll.so` must come from one build, and taking `wineserver` from the same build removes any doubt
   about protocol drift between the rootfs's build and this repo's HEAD. Other PE modules import
   ntdll by name and are unaffected.
3. **Unverified**: whether the shipped rootfs was built from this repo's current HEAD, and with which
   configure flags. The 1x regression test below is the gate that actually answers it.

## Dropping the build into the container

1. Add the modules to the workflow's collect step, which today lists only quartz/winegstreamer.
2. Replace those members inside `app/app/src/main/assets/rootfs.tzst`, leaving everything else
   byte-identical, exactly as commit `adb353b` did for `quartz.dll`.
3. Re-run `scripts/patch_tzst.py`. Skipping it leaves `com.winlator` paths in the new members and the
   container will not boot.
4. Bump `LATEST_VERSION` in `app/app/src/main/java/com/winlator/xenvironment/RootFSInstaller.java`.

## What this does not cover

- **`rdtsc`.** box64 emulates it from the ARM hardware counter without going through ntdll, so a game
  timing itself that way is unaffected. box64 ships as a binary here, so there is no hook for it.
- **Fast forward beyond the device's headroom.** A fixed-timestep game at 2x needs twice the CPU and
  GPU; a title already at full load will not reach the requested factor. Slow motion always works.
- **PulseAudio.** The audio-rate half of the feature lives in `ALSAClient`; the PulseAudio driver is
  a prebuilt binary and keeps running at 1x.
