# NX809J native SELinux enforcing — VALIDATED 2026-06-24

## What this is
Native SELinux enforcing baked into the **vendor.img** (no KSU module). The device
otherwise boots permissive (`androidboot.selinux=permissive`, BoardConfig); these rules
are present in the loaded policy and the device runs enforcing cleanly with `setenforce 1`.

## How it works (validated on-device)
The LineageOS-built vendor.img is a curated subset that won't boot (missing ~638 stock
files: keymint TEE deps, GPU drivers, etc.). So instead we take the **known-booting STOCK
vendor** and append our enforcing rules to `/vendor/etc/selinux/vendor_sepolicy.cil` in CIL
form. init recombines (our plat/system_ext/product + this vendor cil + odm) at boot →
rules load natively. secilc-validated offline (no brick); boots clean (~12s); `setenforce 1`
→ Enforcing, stable, 0 critical denials.

## Files
- `native_enforcing_rules.cil` — 93 rules (91 from the runtime-validated KSU set, translated
  magiskpolicy→CIL, minus 1 KSU-only `adbroot`; + 2 hal_light battery/usb-sysfs rules found
  during the on-device enforcing soak).
- `enforcing_rules.magiskpolicy` — same rules in magiskpolicy format (KSU module / runtime).
- `build_native_enforcing_vendor.sh` — reproducible build (stock vendor + CIL → vendor.img).

## To ship a boots-enforcing-by-default build
1. `build_native_enforcing_vendor.sh` → vendor.img → lpmake super (see build_super_v20_enf.sh).
2. Drop `androidboot.selinux=permissive` from BoardConfig.mk + rebuild/patch vendor_boot.
Flashes via the UNCHANGED XDA method (it's just a super.img + vendor_boot).

## Provenance
Supersedes the KSU-module enforcing approach (which works but is an optional runtime module).
The source-HAL built-vendor effort (vendor_hals.mk, display patches) is a separate WIP for
official LOS (#18) and is NOT part of this enforcing path.

## VALIDATED boots-enforcing-by-default (2026-06-24, super_v32)
**Result: getenforce=Enforcing at boot, 0 blocked denials, UI healthy, keystore2/
onekeymint/gatekeeper all alive — STABLE through soak.**

Approach = DEFERRED enforce (not first-stage):
- Boot PERMISSIVE (androidboot.selinux=permissive in vendor_boot) so the stock
  init-domain security HALs (onekeymint/gatekeeper) + keystore2 connect cleanly.
- product/etc/init/nx809j-enforce.rc flips `write /sys/fs/selinux/enforce 1` on
  property:sys.boot_completed=1 -> native Enforcing, no KSU module.
- vendor.img = stock vendor + native_enforcing_rules.cil (161 rules) appended to
  vendor_sepolicy.cil, labeled with the BUILD file_contexts (NOT stock fc — stock fc
  left core paths unlabeled and crash-looped surfaceflinger/zygote).

WHY NOT first-stage: 4 attempts (super_v25-v30) all froze/bootlooped. Root cause =
stock vendor runs onekeymint/gatekeeper in the INIT domain; under first-stage
enforcing they fail to register / take divergent paths -> crash-loop -> init queue
flood -> freeze. No rule set fixed it (permissive sweep can't predict enforce-path
denials). Deferred-enforce is the correct design for init-domain HALs.

Build: build_native_enforcing_vendor.sh (build-fc) -> super; ship product.img with
nx809j-enforce.rc; vendor_boot stays permissive. XDA flash method UNCHANGED.
