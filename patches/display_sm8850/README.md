# NX809J source display (sm8850) — local_manifest + patches

The NX809J (SM8850 / canoe) uses a **source-built QTI display HAL** (composer /
allocator / demura / SDM core) instead of the stock prebuilt display blobs.

## Why
The stock display blobs link the QTI `vendor.qti.hardware.display.composer3`
interface at **V1**; Android 16 / a source composer needs **V4**. You can't have
one AIDL interface at two versions in one image, which is why every earlier
attempt with the LineageOS **sm8750** display source failed (it only froze
composer3-V1/V2 + aiqe-V2). The **OnePlus-SM8850-Development** display source
(branch `lineage-23.2-caf-sm8850`) targets **composer3-V4 / config-V13 / aiqe-V3**,
which *matches* the NX809J stock display blobs — so it links coherently.

## How it's wired
1. `local_manifests/nx809j.xml` (`<remove-project>` + `<project>`) swaps the three
   LineageOS sm8750 display repos for the OnePlus-SM8850 ones, pulled to the same
   `hardware/qcom-caf/sm8750/display/{core,hal,intf}` paths (so the device's
   existing sm8750 display soong-namespace + wiring is reused unchanged).
2. These patches (applied by `../apply-patches.sh`) carry the NX809J port fixes on
   top of that pristine sm8850 source:
   - **core/0001** — `HWDeviceDRM::SetupAtomic` bounds-check `i < layer_exts.size()`
     before `layer_exts.at(i)` (the ZTE panel has fewer `layer_exts` than
     `hw_layers`; the missing check threw `vector::at()` out_of_range → composer
     SIGABRT on the first DRM atomic commit — OnePlus panels never trip it);
     `libvmmem` header-lib graft (snapalloc needs it, the OnePlus manifest pulls it
     from a separate repo); explicit header_libs on libsdmdal / libdrmutils.
   - **hal/0002** — wire `nx809j_qti_display_uapi` (msm_drm_aiqe.h / sde_drm.h) +
     `libdrmutils_local_headers_nx809j` into `display_headers`; `qmaa` soong_namespace
     ref sm8850→sm8750.

## Also required (in the device / vendor tree, not here)
- `device/nubia/NX809J/vendor_hals.mk`: `composer_version=v3_4` (selects
  V13/aiqe-V3/composer3-V4) **and** `qtidisplay.default=true` (activates the
  `display_headers`/kernel-uapi soong-config header branch — without it the display
  modules get no headers).
- `vendor/nubia/NX809J/Android.bp`: the stock display **service** prebuilts
  (composer/allocator/demura/snapalloc) set `enabled:false` (they otherwise pull
  composer3-V1); the display **libs** (libsdm*/libdrmutils/libsdmclient/
  libgpu_tonemapper) set `prefer:false` so the source builds. The stock
  `composer3-V1-ndk.so` is *kept* — the no-source color libs (libsdm-color, etc.)
  still link it; V1 + V4 coexist fine at runtime.

## Regenerating these patches
If the OnePlus-SM8850 upstream moves, or a fix changes: clone the three repos at
`lineage-23.2-caf-sm8850`, copy the changed files from the working tree over the
pristine clone, `git commit`, `git format-patch -1 --stdout > <this>/…/NNNN-….patch`.
Verified apply target: pristine `lineage-23.2-caf-sm8850`.
