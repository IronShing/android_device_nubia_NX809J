# NX809J self-hosted CI (LineageOS 23.2 / Android 16)

`workflows/build.yml` rebuilds the ROM on a **self-hosted runner** on the build machine and
publishes a GitHub Release. It operates on the **existing tree** (no 268 GB checkout/sync per
run) and runs `patches/apply-patches.sh` first so the out-of-tree fixes are always present.

This is **Track-A #3** toward official (see `~/lineage-scratch/PATH_TO_OFFICIAL.md`): a
reproducible, one-button build. It is **not** a plain `brunch` — the shipped image is a
source + stock-vendor **hybrid** (below).

## Audit — reproducible vs manual (`m dist` output vs the hand-assembled super)

`m dist` (lunch `lineage_NX809J-bp4a-userdebug`) produces, in `out/dist/`:
`lineage_NX809J-target_files-*.zip` (signing input), `…-img-*.zip`, `…-ota-*.zip`, `otatools.zip`.
The `img.zip` ships **`super_empty.img`** (5 KB metadata only) + the individual partition
images — it does **not** populate a flashable super. So the super is assembled by `lpmake`
(`build_super_ci.sh`, fixed NX809J geometry: `super:19327352832`, 3 slots, `--virtual-ab`,
group `19323158528`, empty `_b`). What goes in each slot:

| partition          | source                                             | reproducible from source? |
|--------------------|----------------------------------------------------|---------------------------|
| system             | **build** (`$OUT/system.img`, signed if release)   | ✅ yes                     |
| system_ext         | **build**                                          | ✅ yes                     |
| product            | **build**                                          | ✅ yes                     |
| vendor             | **stock B13MR** + enforcing CIL + ultrawide `.so` patch (`uw_super/vendor_uw.img`) | ❌ manual — source vendor is a non-booting subset (keystore2/TEE + ~638 blobs; see `vendor_img_build_native_enforcing`) |
| odm                | **curated** (`uw_super/odm_uw.img`)                | ❌ manual — source `odm.img` is ~737 KB (near-empty) |
| vendor_dlkm        | **stock** (`ci_inputs/vendor_dlkm.img`)           | ❌ manual — must match the prebuilt kernel's modules |
| system_dlkm        | **stock** (`ci_inputs/system_dlkm.img`)           | ❌ manual                  |
| boot chain         | **build**, but kernel = prebuilt WildKernels Image | ⚠️ built from a prebuilt (below) |

**Kernel / boot** — `BoardConfig.mk: TARGET_PREBUILT_KERNEL := device/nubia/NX809J-kernel/prebuilt/Image`.
That Image is the **WildKernels OP-WILD KernelSU-Next + SUSFS** build (Coding-BR Actions, OP15),
camera-patched (PR Coding-BR#1), committed as a prebuilt in `IronShing/android_kernel_nubia_NX809J`.
The CI **syncs** it (via the manifest) and `m` builds `boot.img` from it — it does **not** rebuild
the kernel. **Updating the kernel is a manual external step** (fetch a new Coding-BR artifact →
commit to the kernel repo). Official LOS forbids KSU/SUSFS + prebuilt kernels — this is a Track-B
blocker, not fixable in CI.

**Net:** everything the *ROM* changes (system/system_ext/product) is source-reproducible and
signable; the `vendor/odm/dlkm` mix and the kernel are static hand-built inputs the CI consumes
from stable paths (`uw_super/`, `ci_inputs/`). Regenerating those inputs is the multi-week Track-B
source-vendor port + a source-kernel tree.

## What the workflow does
1. `apply-patches.sh` — restores frameworks/base, vendor/lineage, CarrierConfig fixes (durability).
2. *(optional)* `repo sync` manifest + local_manifests (`--force-sync`), then re-apply patches.
3. **Build** — `source build/envsetup.sh`; `unset -f grep egrep fgrep`; `lunch bp4a-userdebug`
   (never piped); `nice … NINJA_HIGHMEM_NUM_JOBS=2 m -j6 dist` (higher `-j` OOMs the box).
4. *(optional)* **Release-keys signing** — `sign_target_files_apks -d vendor/lineage-priv/keys`
   → `img_from_target_files` → signed `system/system_ext/product` feed the super; signed OTA kept.
   *(AVB is bypassed by the patched ABL, so vbmeta keys don't matter for boot.)*
5. **Assemble super** — `build_super_ci.sh` (source partitions + stock vendor/odm/dlkm).
6. **Package** — boot chain + `super.img` (zstd + split for the 2 GB/file release cap) + **MD5SUMS**
   + **CHANGELOG** (from device-tree git log) + SHA256SUMS.
7. **Publish** — GitHub Release via `GITHUB_TOKEN`.

## One-time setup (needs GitHub access)
1. **Register the runner** on `IronShing/android_device_nubia_NX809J`:
   Settings → Actions → Runners → New self-hosted runner → Linux. Run `./config.sh` **as `beast`**
   (inherits the tree + build deps); add label **`nx809j`**. Run as a service:
   `sudo ./svc.sh install beast && sudo ./svc.sh start`.
2. **Repo Variables** (Settings → Actions → Variables), all optional — defaults match this box:
   `LINEAGE_TREE`, `SUPER_SCRIPT` (=`build_super_ci.sh`), `KEYS_DIR`, `LUNCH`, `BOOTCHAIN_DIR`.
3. **Workflow permissions** → Read and write (so `GITHUB_TOKEN` can publish releases).
4. **Trigger** — Actions → *Build NX809J* → Run workflow. `dist` on (default) for target-files;
   flip `release_keys` on once you've validated the key set signs cleanly (first run may surface a
   missing per-package key — it's a template, validate once).

## Known caveats (be honest)
- **Signing is unvalidated in CI** until its first real run — `sign_target_files_apks` needs the
  full key set in `vendor/lineage-priv/keys` (releasekey/platform/shared/media/networkstack/APEX…).
- **Manual inputs must stay put** — `uw_super/{vendor_uw,odm_uw}.img` and `ci_inputs/*dlkm.img`.
  Don't archive them in disk cleanups.
- **Not a from-source `brunch`** — official submission needs Track-B (source vendor + source
  kernel). This CI makes the *unofficial* reproducible, which is the on-ramp LOS expects.

## Artifact size
The super is ~19 GB raw → zstd + split (`super.img.zst.part00…`). Reassemble:
`cat super.img.zst.part* | zstd -d -o super.img`. Boot-chain images upload whole.
