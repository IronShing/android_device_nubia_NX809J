# LineageOS 23.2 device tree — ZTE Nubia RedMagic 11 Pro (NX809J / `canoe`)

A **device-specific** LineageOS 23.2 (Android 16 / SDK 36) port for the RedMagic 11 Pro
(Snapdragon 8 Elite Gen 5 / SM8850). This is a real device port — **not a GSI overlay**:
`system`, `system_ext`, `product`, and `odm` are all built from this tree and report
`lineage_NX809J`.

## Status
**First known device-specific LineageOS boot on this device** (`sys.boot_completed=1`, ~33 s
to home screen). Public efforts before this had only booted GSIs. Functional after first boot:
display (60–144 Hz, HDR10/HLG), GPU, Wi-Fi, Bluetooth, NFC, camera HAL, sensors, audio, GPS,
RIL. Work in progress: fingerprint (under-display Goodix udfps HAL), advanced haptics, fan/RGB
controls, SELinux enforcing. Current images are a **debug boot** (permissive SELinux via
vendor_boot cmdline) — see "Hardening TODO".

## Approach
The from-source kernel is **unbuildable** (ZTE shipped incomplete GPLv2 sources — missing
`canoe.fragment`, drivers). So this port **rides the stock GKI kernel + DTB** (prebuilt) and
builds only the LineageOS userspace, paired with the **stock vendor** partition.

Two things are essential and non-obvious:
1. **Build at the `bp4a` release config, not `bp2a`.** Both yield SDK 36, but `bp4a` is
   LineageOS's release config (carries the correct app aconfig flag values). `bp2a` leaves
   LineageOS-app flags at AOSP defaults, which breaks Trebuchet (recents/back-gesture crash)
   and the status-bar clock, among others.
2. **Force a runtime SELinux compile.** Framework (LineageOS) + vendor (stock) are built
   separately, so a precompiled `odm` sepolicy can never match. Strip
   `odm/etc/selinux/precompiled_sepolicy` (+ its `.sha256`) so `init` runtime-compiles the
   present CIL (which includes the stock vendor's ~30 Nubia property types).

## Build
```
# repo sync with the local manifest (device + prebuilt kernel + vendor):
#   .repo/local_manifests/nx809j.xml  (see the IronShing/NX809J meta repo)
source build/envsetup.sh
lunch lineage_NX809J-bp4a-userdebug        # bp4a — NOT bp2a
m -j<N> systemimage systemextimage productimage odmimage
```
Then assemble a device `super` from the built framework images + the **stock BP2A vendor /
vendor_dlkm / system_dlkm**, with the `odm` precompiled sepolicy stripped (above), and flash it
alongside the stock boot chain.

## The walls (how it got to boot)
- **Wall 1 — qseecom CMA:** memory reservation; fixed in the prebuilt DTB.
- **Wall 3 — system-as-root:** `/system` must carry the root mount-point layout; produced by a
  proper source build (no patch needed once built correctly).
- **Wall 9a — sepolicy compile:** use the fresh LineageOS `system_ext` (the stock one's
  `vendor_voiceui_app` type failed to resolve).
- **Walls 9b/10 — property area / runtime sepolicy:** strip the `odm` precompiled policy so
  `init` runtime-compiles with the stock vendor's property types (otherwise
  `Failed to initialize property area`).
- **Wall 11 — `/data` read-only:** wipe `/data` + `/metadata` (the device ships CN encryption);
  LineageOS sets up fresh FBE on first boot.
- **Wall 12 — `com.android.bt` APEX:** declared `min_sdk 36` vs an SDK-35 platform. Fixed
  properly by building the platform at SDK 36.
- **Wall 13 — stock-vendor SDK-36 APEXes:** `com.android.hardware.cas` etc. are vendor-signed
  SDK-36 APEXes that cannot be rebuilt. Fixed by aligning the **platform to SDK 36 (bp4a)** so
  it accepts them — framework ≥ vendor (correct Treble), instead of the backwards
  SDK-35-framework-on-SDK-36-vendor we started with.

## Hardening TODO (current boot is debug)
Permissive SELinux (via `vendor_boot` cmdline), `flags=3` vbmeta, debuggable/adb-open. To go
production: collect the full `avc` denial set and move to enforcing (e.g. ensure `odm` files are
properly labeled, not `unlabeled`); proper vbmeta; validate the udfps fingerprint, haptics,
camera, and thermal HALs.

## Credits
Bring-up by IronShing. Stock kernel/vendor from ZTE/Nubia firmware (BP2A.250605, REDMAGICOS 11).
