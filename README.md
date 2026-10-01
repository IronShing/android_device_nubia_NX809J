# LineageOS 23.2 device tree — ZTE Nubia RedMagic 11 Pro (NX809J / `qwjujube`, SM8850 "canoe")

A **device-specific** LineageOS 23.2 (Android 16 / SDK 36) port for the RedMagic 11 Pro
(Snapdragon 8 Elite Gen 5 / SM8850). This is a real device port — **not a GSI overlay**:
`system`, `system_ext`, `product`, and `odm` are all built from this tree and report
`lineage_NX809J`. The same tree (branch `nx809j-evo12`) builds the EvolutionX A17 ROM that
is shipped and user-tested daily; the LineageOS 23.2 branch is `nx809j-sourcedisplay`.

## Status (2026-10)
Shipping. Display 60–144 Hz (source-built sm8850 display stack), HDR, GPU, Wi-Fi, BT, NFC,
FeliCa, camera (incl. 50 MP full-size and the 17 MP front path), udfps fingerprint, sensors,
audio, GPS, RIL/VoLTE/VoNR, fan, RGB, shoulder triggers, charge separation, external
display. See the XDA thread for the user-facing changelog. SELinux: see the comment block in
`BoardConfig.mk` and `enforcing/`.

## Approach
The from-source kernel is **unbuildable** from ZTE's GPL drop (missing `canoe.fragment` and
drivers), so this port rides a **prebuilt GKI kernel + DTB** (`device/nubia/NX809J-kernel`)
and builds only the userspace. `/vendor`, `vendor_dlkm` and `system_dlkm` on the phone come
from a prepared stock-derived donor (see "Super image" below); the vendor repo supplies the
blobs the userspace build links against.

Two things are essential and non-obvious:
1. **Build at the `bp4a` release config, not `bp2a`.** Both yield SDK 36, but `bp4a` is
   LineageOS's release config (carries the correct app aconfig flag values). `bp2a` leaves
   LineageOS-app flags at AOSP defaults, which breaks Trebuchet (recents/back-gesture crash)
   and the status-bar clock, among others.
2. **No precompiled sepolicy.** Framework and vendor are built separately, so a precompiled
   `odm`/`vendor` sepolicy can never match. `precompiled_sepolicy` (+ `.sha256`) must be
   absent so `init` runtime-compiles the CIL at boot (the donor vendor has it stripped).

## Build (from an empty directory)
```
repo init -u https://github.com/LineageOS/android.git -b lineage-23.2
mkdir -p .repo/local_manifests
curl -o .repo/local_manifests/nx809j.xml \
  https://raw.githubusercontent.com/IronShing/android_device_nubia_NX809J/nx809j-sourcedisplay/manifest/nx809j.xml
repo sync -j8
bash device/nubia/NX809J/patches/apply-patches.sh                 # committed patches (frameworks, display HAL — mandatory)
APPLY_WORKTREE_DIFFS=1 bash device/nubia/NX809J/patches/apply-patches.sh   # LineageOS-derived trees only (see patches/README.md)
bash device/nubia/NX809J/tools/post_sync_fixups.sh                # tree-wide mechanical edits (min_sdk 36->35 ...)
bash device/nubia/NX809J/patches/apply-source-display.sh          # SDM-core prefer flips in vendor Android.bp
source build/envsetup.sh && unset -f grep
lunch lineage_NX809J-bp4a-userdebug        # bp4a — NOT bp2a
m -j6 systemimage systemextimage productimage odmimage
```
Branches: device + vendor `nx809j-sourcedisplay`, kernel `lineage-23`, display source
`OnePlus-SM8850-Development/*` @ `lineage-23.2-caf-sm8850` (all pinned in the manifest).
Detached builds (systemd/cron) must `export LINEAGE_BUILD=NX809J` and
`LLVM_AOSP_PREBUILTS_VERSION` first. Framework-heavy builds OOM at `-j12` on 48 GB; use `-j6`.
GApps-bearing variants need the per-partition privapp permission allowlists or the boot
animation hangs (`Privileged permission … not in privapp-permissions allowlist` in logcat).

## Super image and flashing (not in the tree)
Take the current EvolutionX kit from the XDA thread and `lpunpack` its `super.img`
(`simg2img` first — it is sparse). Reuse its `vendor_a`, `vendor_dlkm_a`, `system_dlkm_a`
(and `odm_a` if the tree-built odm fails to boot); use the freshly built `system`,
`system_ext`, `product`, `odm`. Assemble with exactly this geometry (19 GB super, Virtual
A/B, only the `_a` slot populated):
```
lpmake --metadata-size 65536 --super-name super --metadata-slots 3 \
  --device super:19327352832 --block-size 4096 --virtual-ab \
  --group qti_dynamic_partitions_a:19323158528 --group qti_dynamic_partitions_b:19323158528 \
  --partition system_a:readonly:$(stat -c %s system.img):qti_dynamic_partitions_a --image system_a=system.img \
  --partition system_ext_a:readonly:$(stat -c %s system_ext.img):qti_dynamic_partitions_a --image system_ext_a=system_ext.img \
  --partition product_a:readonly:$(stat -c %s product.img):qti_dynamic_partitions_a --image product_a=product.img \
  --partition odm_a:readonly:$(stat -c %s odm.img):qti_dynamic_partitions_a --image odm_a=odm.img \
  --partition vendor_a:readonly:$(stat -c %s vendor_a.img):qti_dynamic_partitions_a --image vendor_a=vendor_a.img \
  --partition vendor_dlkm_a:readonly:$(stat -c %s vendor_dlkm_a.img):qti_dynamic_partitions_a --image vendor_dlkm_a=vendor_dlkm_a.img \
  --partition system_dlkm_a:readonly:$(stat -c %s system_dlkm_a.img):qti_dynamic_partitions_a --image system_dlkm_a=system_dlkm_a.img \
  --sparse --output super.img
```
`adb sideload` does not work on this device (Virtual A/B with compression needs `/data` for
the COW snapshot and recovery cannot mount the metadata-encrypted `/data`), so updates
rewrite `super` over EDL (9008) with `qdl`. Use the kit folder as the template: it carries
`qdl`, `loaders/`, `rawprogram_update.xml` (super + dtbo, keeps `/data` and root),
`rawprogram_full.xml`, `update_edl.sh` / `flash_edl.sh`. Drop in your `super.img`, keep the
kit's `boot`/`init_boot`/`vendor_boot`/`dtbo`/`vbmeta*`/`recovery` (prebuilt kernel chain
matching the donor vendor), regenerate `MD5SUMS.txt`. Keep `super.img` sparse and let
`rawprogram`'s `sparse="true"` handle it — never `qdl write` a sparse image directly (it
destroys the lp metadata). Stop ModemManager before `qdl` on Linux; the super write takes up
to two minutes, do not interrupt; after `qdl` returns the phone stays in EDL — hold POWER
~5 s.

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

## Credits
Bring-up by IronShing. Stock kernel/vendor from ZTE/Nubia firmware (BP2A.250605, REDMAGICOS 11).
sm8850 display source: OnePlus-SM8850-Development (petalFTW). Shoulder-trigger merged-touch
research: austineyoung2000/Redmagic-Trigger-Bridge.
