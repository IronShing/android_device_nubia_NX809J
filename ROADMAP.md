# NX809J — Post-beta roadmap

Beta ships on the **stock prebuilt GKI kernel** (Google `…ab13761046`, `TARGET_PREBUILT_KERNEL`)
and **permissive** SELinux — both stable and done. Everything below is a *post-beta*
enhancement; none of it gates the beta.

## v2 — From-source GKI kernel (KernelSU + SUSFS)
**The big one.** Replace the stock prebuilt kernel with a from-source GKI build based on
the Coding-BR `OnePlus_KernelSU_SUSFS` fork (RedMagic 11 Pro / NX809J), built from
`IronShing/android_kernel_nubia_NX809J`. Owned by adriano / КСНЧИК (kernel-build track).

Unlocks three things the prebuilt kernel can't:
- **KernelSU** — kernel-level root.
- **SUSFS** — hides the unlocked bootloader + permissive SELinux + root from app
  attestation → **Play Integrity passes** → banking apps / Google Pay work. (This makes
  the permissive-SELinux limitation *moot for integrity* — no need to chase enforcing.)
- **Screen-off fingerprint** — a source kernel lets us patch the FOD low-power scan
  arming (the one thing blocking screen-off UDFPS; impossible on a prebuilt kernel).

Prereqs / notes:
- adriano has **disabled kernel signature verification** so an unsigned kernel can boot.
- Boots from internal storage / via KernelSU — **install TWRP + keep on-device backups**
  before testing (fast recovery, as adriano noted).
- Risk: booting unsigned/custom kernels — always have a verified rollback.
- Build inputs to diff against the working prebuilt: kernel config + module list captured
  in `~/android/fwlogs_NX809J.tgz` (`/proc/config.gz` → 06, `/proc/modules` → 07).

## Other post-beta items
- **Screen-off UDFPS** — depends on the v2 kernel (FOD scan arming). See memory
  fp_screen_off_udfps.
- **Public-build FP** — current cal is per-unit (single-unit build). A public build needs
  on-device self-calibration (Goodix no-chart opcode 5634) instead of the shipped cal.
- **Multi-carrier VoLTE** — CarrierConfig is Zain-KW (41902)-specific; add overlays for
  other carriers as needed.
- **IronShing forks** (optional) — the out-of-tree fixes are already durable via
  `patches/` + `apply-patches.sh`; forking frameworks/base / vendor/lineage / CarrierConfig
  to IronShing is nicer-but-optional polish (see `patches/README.md`).
- **SELinux enforcing** — NOT planned: blocked by the stock-vendor denial cascade
  (unpatchable on a stock-vendor ride). Ships permissive; SUSFS (v2) makes it moot for
  app integrity. See memory enforcing_charging_hal_blocker.
