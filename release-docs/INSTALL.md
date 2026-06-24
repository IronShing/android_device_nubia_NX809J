# Install Guide — LineageOS 23.2 (Android 16) — RedMagic 11 Pro (NX809J / canoe)
### Build 2026-06-22 (beta) — VoLTE, SIM hot-swap, fingerprint, charge-limit + enforcing

---

## ⚠️ READ THIS FIRST
- **DO NOT take OTA updates and DO NOT flash any firmware newer than what's on your device.** The RM11 series has hardware eFuse / anti-rollback — a newer firmware can **permanently lock your bootloader with no recovery.** Disable the system updater.
- **dm-verity / AVB are disabled** (required for it to boot). The ROM **boots permissive** by default; SELinux **Enforcing** is available as an optional KernelSU module (see "Root & Enforcing").
- **Flashing wipes all your data. Back up first.**
- This is a **beta** — most things work now (list at the bottom), but a few don't (deep-sleep fingerprint, front-camera video, payments).
- **NX809J only.** Do not use on other models.

---

## PREREQUISITES (do these once)
1. **Unlock the bootloader** via the ZTE Family Toolbox method — XDA thread: https://xdaforums.com/t/4780930/
2. **Root your current firmware with Magisk** — needed for **Method A** (the stock recovery's fastbootd rejects these flashes, so you flash from the **LineageOS recovery's** fastbootd, and root places that recovery). *Skip if using the EDL method (Method C).*
3. Install **platform-tools** (adb + fastboot) and put them in PATH.

---

## DOWNLOADS (this pCloud folder)
Grab:
1. `NX809J-LineageOS-23.2-bootchain.zip` — boot images, **LineageOS `recovery.img`**, flash scripts
2. `super.img` (~3.4 GB, sparse) — the ROM

Put both in one folder, extract the zip there (so `super.img` sits next to `flash_all.sh`).
Verify with `MD5SUMS.txt`.

---

## METHOD A — One-click via LineageOS recovery (recommended)
1. Phone booted into your **rooted** firmware, USB-debugging on.
2. In the folder with the extracted files + `super.img`:
   - **Windows:** double-click `flash_all.bat`
   - **Linux/macOS:** `chmod +x flash_all.sh && ./flash_all.sh`
3. It confirms root, `dd`s the LineageOS recovery to both slots, boots it, enters its fastbootd, flashes the A/B partitions with explicit `_a`, flashes super, wipes data, sets slot a, reboots.

### Method A (manual)
```
# 1) place LineageOS recovery (needs root)
adb push recovery.img /data/local/tmp/recovery.img
adb shell su -c 'dd if=/data/local/tmp/recovery.img of=/dev/block/by-name/recovery_a bs=4096'
adb shell su -c 'dd if=/data/local/tmp/recovery.img of=/dev/block/by-name/recovery_b bs=4096'
adb reboot recovery
# 2) from LOS recovery: adb reboot fastboot   (or menu → Advanced → Enter fastboot)
# 3) flash (explicit _a — this fastbootd reports no slot support; super/erase stay unsuffixed)
fastboot flash boot_a boot.img && fastboot flash init_boot_a init_boot.img && \
fastboot flash vendor_boot_a vendor_boot.img && fastboot flash dtbo_a dtbo.img && \
fastboot flash vbmeta_a vbmeta.img && fastboot flash vbmeta_system_a vbmeta_system.img && \
fastboot flash super super.img && \
fastboot erase metadata && fastboot erase userdata && fastboot erase misc && \
fastboot set_active a && fastboot reboot
```

## METHOD B — userdebug ABL, direct fastbootd
After flashing the **userdebug ABL** (ZTE Toolbox → option 12), fastboot writes are unblocked — same `fastboot flash _a …` sequence as Method A step 3, directly from `adb reboot fastboot` (no recovery dd).

## METHOD C — EDL / qdl (Linux, no root needed) — uses `loaders/` + `tools/`
For when you can't/won't root or use the userdebug ABL. The bootloader's normal fastboot can't write, but **EDL always can**.
```
adb reboot edl          # or: power off, hold Vol-Up+Vol-Down, plug USB
lsusb | grep 05c6:9008  # confirm EDL
bash tools/flash_lineage.sh    # flashes boot chain (LUN4) + super (LUN0) AND wipes /data, ~8-10 min
# then long-press Power ~10s to boot. First boot ~1-2 min (it formats /data).
```
**The script now wipes `/data`+`/metadata` for you via EDL.** This is required coming from
stock — otherwise the first LOS boot hangs at the **boot animation** (adb comes up but it never
reaches the setup wizard), and the bundled recovery can't format `/data` to fix it.

---

## Root, SUSFS & SELinux Enforcing — `extras/`
**This build ships the WildKernels OP-WILD KernelSU-Next + SUSFS kernel as its `boot.img`.**
Root is **dormant** out of the box (a normal flash is a clean LOS; nothing is rooted until you opt in).
1. **Activate root:** install the **KernelSU-Next manager** app → it shows "Working" → grant root. SUSFS is already in-kernel.
2. **SELinux Enforcing (optional):** KernelSU-Next manager → Modules → install `extras/nx809j_enforcing.zip` → reboot (applies the validated policy + `setenforce 1` after boot; without it the device stays permissive).
3. **Prefer a vanilla, no-KSU kernel?** Flash `extras/boot_vanilla-stockGKI.img` to `boot_a` instead of the shipped boot.
4. `extras/Droidspaces_*.zip` = KSU userspace daemon (optional). `extras/OrangeFox-R12.0-NX809J.img` = recovery. `extras/abl_unlock.elf` = bootloader unlock helper.

---

## TROUBLESHOOTING
- **"FAILED (remote: …)"** → you're in the **stock** recovery's fastbootd, not the LOS one (redo Method A 1–2), or use Method C (EDL).
- **`su: not found` / dd fails** → current firmware not rooted (prereq #2).
- **No mobile/VoLTE after boot** → cold-cycle the SIM (toggle airplane mode / re-seat). SIM hot-swap now works without reboot.
- **Stuck at the boot animation** (adb works, never reaches setup wizard, doesn't reboot) → `/data` wasn't formatted (stale stock encryption). The bundled recovery can't format it; wipe `/data` via EDL instead:
  ```
  adb reboot edl   # or hardware combo
  tools/qdl --storage ufs --oem ZTE --include loaders loaders/prog_firehose_ddr.elf erase 0/userdata erase 0/metadata
  ```
  then long-press Power to boot. (The updated `flash_lineage.sh` now does this automatically.)
- **Bootloop / password prompt** → same as above — wipe `/data`+`/metadata` via EDL.
- **Never** accept an update prompt (eFuse — see top).

---

## WHAT WORKS
Boot (Android 16) · Display / GPU · **AOD** · Wi-Fi · Bluetooth + BT audio + **LE Audio (LC3)** · NFC · Sensors · Audio · GPS · **VoLTE calls + SMS + mobile data** · **Call recording** · **SIM hot-swap (no reboot)** · Camera (rear photo+video, front photo) · IR blaster · **DeX-like desktop mode** (external display + on-device freeform) · **Double-tap-to-wake** · **Fingerprint** (enroll + screen-on / AOD unlock) · **Charge limit** (Charging Control + correct "defend" status-bar icon) · **SELinux Enforcing** (optional KSU module)

## WHAT DOESN'T (yet)
**Deep-sleep fingerprint** (press FP from a fully-off screen — AOD-state works) · front-camera **video** (under-display dots; photos fine) · secure element / IFAA (payment apps)

---

*Source: github.com/IronShing/android_device_nubia_NX809J. Kernel: Coding-BR OnePlus_KernelSU_SUSFS (OP-WILD). Unofficial, test-key-signed. Beta — feedback welcome.*
