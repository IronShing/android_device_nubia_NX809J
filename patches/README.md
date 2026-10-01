# NX809J out-of-tree patches

> **Android 17 / EvolutionX cnb (branch `nx809j-evo12`): use `a17/` only.**
> `bash patches/a17/apply-a17-patches.sh` applies the seven one-commit fixes that live in repos
> we did not fork (build/make signing, device/qcom/sepolicy, sepolicy_vndr/sm8750,
> hardware/qcom-caf/common, Glimpse, SecureElement, modules/Nfc). Everything else NX809J
> touches is an IronShing fork pinned by `manifest/nx809j.xml`. The directories below
> (`frameworks_base/`, `vendor_lineage/`, `display_sm8850/`, `apply-patches.sh`, ...) are the
> **LineageOS 23.2 / Android 16** set and will not apply to the cnb forks.


Fixes that live in **shared LineageOS/AOSP repos we do not fork**, kept here as patches
so they survive a `repo sync` (the device tree is forked, so this dir always persists).

| Repo | Branch (if present) | Patch | What |
|------|--------------------|-------|------|
| `frameworks/base` | `nx809j` | `frameworks_base/0001` | BatteryService → `CHARGING_POLICY_ADAPTIVE_LONGLIFE` when plugged-but-discharging (charge-limit defend glyph / no false bolt) |
| `frameworks/base` | `nx809j` | `frameworks_base/0002` | Kotlin/Soong build-compat fixes for LOS-23.2/Android-16 (**tree won't compile without these**) |
| `vendor/lineage` | `nx809j` | `vendor_lineage/0001` | prebuilt-kernel var defaults, disable CUSTOM_LOCALES, drop Twelve |
| `packages/apps/CarrierConfig` | `nx809j-volte-41902` | `packages_apps_CarrierConfig/0001` | VoLTE enable for Zain KW (41902) |

## Restore after a repo sync
```
bash device/nubia/NX809J/patches/apply-patches.sh
```
Idempotent — already-applied patches are skipped.

## Optional: forks instead of patches (nicer, needs GitHub)
Fork each repo to IronShing, push the branch above, and add to
`.repo/local_manifests/nx809j.xml`:
```xml
<remove-project name="LineageOS/android_frameworks_base" />
<project name="IronShing/android_frameworks_base"
         path="frameworks/base" remote="github" revision="lineage-23.2" />

<remove-project name="platform/packages/apps/CarrierConfig" />
<project name="IronShing/android_packages_apps_CarrierConfig"
         path="packages/apps/CarrierConfig" remote="github" revision="lineage-23.2" />

<remove-project name="LineageOS/android_vendor_lineage" />
<project name="IronShing/android_vendor_lineage"
         path="vendor/lineage" remote="github" revision="lineage-23.2" />
```
Regenerate patches after changing the branches:
```
git -C frameworks/base format-patch -o device/nubia/NX809J/patches/frameworks_base <base>..nx809j
```
