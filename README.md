# EvolutionX 12 (Android 17) / LineageOS device tree — ZTE Nubia RedMagic 11 Pro (NX809J / `qwjujube`)

> **This branch (`nx809j-evo12`) is the Android 17 tree** behind the EvolutionX 12.x builds in the
> XDA thread. Everything below the A17 section is the original LineageOS 23.2 (Android 16) write-up,
> kept for the port history; its manifest/patch instructions live on the `nx809j-sourcedisplay` branch.

## Android 17 (EvolutionX cnb) — how to build

```
repo init -u https://github.com/Evolution-X/manifest -b cnb --git-lfs
mkdir -p .repo/local_manifests
curl -Lo .repo/local_manifests/nx809j.xml \
  https://raw.githubusercontent.com/IronShing/android_device_nubia_NX809J/nx809j-evo12/manifest/nx809j.xml
repo sync -j8
bash device/nubia/NX809J/patches/a17/apply-a17-patches.sh    # 7 one-commit fixes in un-forked repos
source build/envsetup.sh
lunch evolution_NX809J-cp2a-userdebug      # cp2a — bp4a fails soong (non-deterministic module_sdk errors)
export LINEAGE_BUILD=NX809J                # AFTER lunch (lunch clears it)
m -j6 systemimage systemextimage productimage odmimage   # or a full `m`; -j6 — this tree OOMs at -j12 on 48 GB
```

- `manifest/nx809j.xml` pins **every** repo that carries NX809J changes as an IronShing fork
  (display core/hal/intf, frameworks/base, av, native, opt/telephony, Settings, GameSpace,
  lineage-sdk, vendor/extras, vendor/lineage, hardware/lineage/interfaces, CarrierConfig,
  Launcher3, Dialer, LatinIME). Branch `nx809j-evo12-cnb` = rebased on Evolution-X's `cnb`;
  `nx809j-evo12` = on a LineageOS/AOSP project EvoX inherits unchanged. The old
  `patches/{frameworks_base,vendor_lineage,display_sm8850,...}` + `apply-patches.sh` are the
  **A16/LOS 23.2** set and are not used here — only `patches/a17/`.
- Display: the sm8850 display source (petalFTW's lineage-24.0 snapshot + the NX809J commits) is
  pulled to the `hardware/qcom-caf/sm8750/display/*` path; LOS's sm8750 display stops at
  composer3-V3 and the stock NX809J blobs need V4.
- Variants: `NX809J_MINIMAL=true` (Minimal, GApps core), `NX809J_MICROG=true`, default = FullGApps
  (see `evolution_NX809J.mk`; Android Auto only in Full, `NX809J_SHIP_AA`).
- Super/EDL packaging is the same as on A16: tree-built system/system_ext/product/odm + the
  kit's vendor_a / vendor_dlkm_a / system_dlkm_a, `lpmake` (19 GB virtual A/B, 3 metadata
  slots), flashed over EDL with `qdl` — see the "Source" section of the XDA thread's OP and
  the kit's `update_edl.sh`. Signing: `patches/a17/build_make` is required or
  `sign_target_files_apks` dies with a TypeError on the zipped input.
- Credits for the A17 work are at the bottom (Credits).

---

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
Shoulder-trigger merged-touch engine (`triggermap/trigger_map.c`) ported from Austin Young's
[Redmagic-Trigger-Bridge](https://github.com/austineyoung2000/Redmagic-Trigger-Bridge) (GPL-3.0).
