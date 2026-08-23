# desktop/ — third-party prebuilts (MagicDesk + Shizuku)

Both APKs here are the upstream authors' **unmodified release binaries**. Neither is built from
source in this tree, neither is re-signed, and neither is ours.

| File | Upstream | Version | License | sha256 |
|---|---|---|---|---|
| `MagicDesk.apk` | [mekhontsev/magicdesk](https://github.com/mekhontsev/magicdesk) | 1.7.0 (`versionCode` 170) | MIT | `7f994a3eae4e9a8beda3041343300e8d041d0bf2ab5419a71a8527c3e5ff4f78` |
| `Shizuku.apk` | [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) | 13.6.0.r1086.2650830c (`versionCode` 1086) | Apache-2.0 | `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f` |

MagicDesk's sha256 is the value its author publishes in `SHA256SUMS` on the release. Re-verify on
every bump — the whole point of pinning is that a swap has to be noticed.

## Why Shizuku ships too

Audited MagicDesk 1.7.0 source: every privileged operation goes through one shell service obtained
with `Shizuku.bindUserService()`. There is **no** `su` path, no `libsu`, no `RootService`, and no
app-UID fallback — grep the tree if you want to confirm. The Shizuku API surface it uses is:

```
Shizuku.bindUserService / unbindUserService / UserServiceArgs
Shizuku.pingBinder / getVersion / getUid
Shizuku.checkSelfPermission / requestPermission
Shizuku.addBinderReceivedListenerSticky / addBinderDeadListener
```

`bindUserService` needs a real Shizuku **server**, not just the API stub, so shipping MagicDesk
without Shizuku would ship an app that cannot do anything at all.

## What "Prepare device" actually does

Worth knowing, because it is far less than the docs imply. From `DeviceSetupManager.java`, the whole
of it is two globals and one appop:

```
settings put global enable_freeform_support 1
settings put global force_resizable_activities 1
cmd appops set io.github.mekhontsev.magicdesk SYSTEM_ALERT_WINDOW allow
```

- `enable_freeform_support` is **already redundant here**: the framework treats freeform as enabled
  if the device declares `android.software.freeform_window_management`, which we do in `device.mk`.
  This is the dev-option override for devices that don't.
- `force_resizable_activities` is deliberately **not** baked into the ROM. It forces every activity
  in the system resizable, which changes behaviour for every user whether or not they ever open
  MagicDesk — including games. MagicDesk can set it for people who want it; we don't impose it.
- `SYSTEM_ALERT_WINDOW` is app-scoped and safe, so we do pre-grant it (see `RmApp`). Note that
  preinstalling is *not* sufficient on its own: this platform's `SYSTEM_ALERT_WINDOW` is
  `signature|setup|appop|installer|pre23|development` — no `preinstalled` — so it has to be granted
  explicitly.

## The on-device-display caveat

This ROM ships `config_canInternalDisplayHostDesktops=false`, which is what removes the Android 16
white "app handle" pill. It also disables on-device desktop hosting. External/projected displays —
MagicDesk's main use case — take a different path and are unaffected.

There is **no way to separate the two**. `enable_drawing_app_handle` looks like a candidate but only
selects how the handle is rendered (programmatic vs Drawables); it is a `PURPOSE_BUGFIX` flag, not a
visibility switch. `canInternalDisplayHostDesktops` short-circuits `canEnterDesktopMode()`, so the
pill and internal-display desktop are one switch.

So it is exposed as a user choice instead — RedMagic Control → Desktop, or:

```
cmd overlay enable  com.nx809j.overlay.showapphandle    # on-device desktop + pill
cmd overlay disable com.nx809j.overlay.showapphandle    # neither
```

## Runtime: starting the Shizuku server

Shizuku's server is started by its bundled starter ELF (shipped as `lib/arm64-v8a/libshizuku.so`),
which execs:

```
/system/bin/app_process -Djava.class.path=<apk> /system/bin \
    --nice-name=shizuku_server rikka.shizuku.server.ShizukuService
```

The KSU module `nx809j_shizuku_autostart` does exactly that at boot, so MagicDesk works with no
per-boot tap. Without the module, open Shizuku once per boot and use **Start via root** (this ROM
ships KernelSU-Next, so the wireless-debugging pairing flow in Shizuku's docs is unnecessary).

Starting the server from `init` as `user shell` instead was considered and rejected: under SELinux
enforcing it would need a bespoke system_ext domain with close to system_server reach, which is a
large attack surface for an optional feature. Running it from KSU keeps it opt-in and reversible.

## Building from a clone

Both APKs are `.gitignore`'d — they are the upstream authors' binaries and are not mine to
redistribute inside this tree. A clone therefore has `Android.bp` and this README but no APKs, and
`device.mk` skips the packages automatically (you get a warning, not a build failure).

To include them, download the exact versions in the table above, verify the checksums, and drop them
in as `desktop/MagicDesk.apk` and `desktop/Shizuku.apk`. They are then picked up on the next build.
