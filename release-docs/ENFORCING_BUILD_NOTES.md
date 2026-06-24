# Native SELinux Enforcing build (super_v20) — what changed vs the XDA release

## Does it change the XDA flash method?
**NO.** super_v20 is identical to the released ROM EXCEPT the vendor partition's SELinux
policy. It flashes via the exact same procedure (flash_lineage.sh: boot chain + LOS
verity-disabled vbmeta + super + /data wipe). Nothing about the install steps changes —
enforcing is achieved purely by the super.img content, not by any new flash step.

## What changed inside the image (vs release super)
- vendor partition = STOCK vendor (unchanged, boots, keymint/GPU/etc all work) PLUS
  91 enforcing rules appended to /vendor/etc/selinux/vendor_sepolicy.cil in CIL form
  (the same rules the KSU enforcing module injects at runtime — translated magiskpolicy
  -> CIL, dropped 1 KSU-only `adbroot` rule). init recombines them at boot natively.
- Validated offline with secilc (recombine of plat+system_ext+product+vendor_enf+odm) -> OK, no brick.
- Everything else (system, system_ext, product, odm, boot chain, vbmeta) = same as release.

## To make it boot ENFORCING by default (vs permissive-with-rules-loaded)
super_v20 still boots PERMISSIVE (androidboot.selinux=permissive in vendor_boot cmdline,
BoardConfig:125). The 91 rules ARE in the loaded policy; flip with `setenforce 1` to verify.
For native boot-enforcing: drop that cmdline + rebuild vendor_boot (a separate vendor_boot
flash). So a "native enforcing release" = this super.img + a vendor_boot without the permissive flag.

## Test result (this flash)
TEST DATE: 2026-06-24. Flashed super-only on a fresh (stock-reset) slot. RESULT: **BOOTED CLEAN ~12s**, no method change needed. setenforce 1 -> Enforcing, STABLE (surfaceflinger up). Rules confirmed native in /vendor cil (NO KSU module). Only 2 new blocked denials found (hal_light_default -> vendor_sysfs_battery_supply/usb_supply dir search, the ZTE charging-LED daemon) -> now added to the rule set. With those folded in, expect 0 denials. GOAL: native enforcing via built vendor.img = ACHIEVED.
