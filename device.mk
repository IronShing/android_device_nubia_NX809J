#
# Copyright (C) 2025 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# A/B
$(call inherit-product, $(SRC_TARGET_DIR)/product/virtual_ab_ota/launch_with_vendor_ramdisk.mk)

AB_OTA_POSTINSTALL_CONFIG += \
    RUN_POSTINSTALL_system=true \
    POSTINSTALL_PATH_system=system/bin/otapreopt_script \
    FILESYSTEM_TYPE_system=erofs \
    POSTINSTALL_OPTIONAL_system=true

AB_OTA_POSTINSTALL_CONFIG += \
    RUN_POSTINSTALL_vendor=true \
    POSTINSTALL_PATH_vendor=bin/checkpoint_gc \
    FILESYSTEM_TYPE_vendor=erofs \
    POSTINSTALL_OPTIONAL_vendor=true

PRODUCT_PACKAGES += \
    checkpoint_gc \
    otapreopt_script

# API — device ships with Android 16 / SDK 36 (bp4a release config)
PRODUCT_SHIPPING_API_LEVEL := 36



# Boot animation (1216x2688 from display extraction)
TARGET_SCREEN_HEIGHT := 2688
TARGET_SCREEN_WIDTH := 1216

# Dynamic partitions
PRODUCT_USE_DYNAMIC_PARTITIONS := true

# Fastbootd
PRODUCT_PACKAGES += \
    fastbootd

# Filesystem
PRODUCT_PACKAGES += \
    fs_config_files

# Desktop "app handle" toggle: mutable /product/overlay RRO (config=true). Build defaults to hidden
# (device framework-res overlay sets config_canInternalDisplayHostDesktops=false); the Evolver
# "Show app handle" switch (Settings > Evolution X > Miscellaneous) enables this overlay to reveal it.
PRODUCT_PACKAGES += \
    ShowAppHandleOverlay

# Desktop environment: MagicDesk (third-party, MIT) + Shizuku, its only privilege transport.
# Ordinary presigned /product/app apps, deliberately NOT privileged and NOT platform-signed —
# MagicDesk asks its own shell service for everything, never the platform. Kept updatable so users
# can take upstream releases directly. Provenance, checksums, the audit of why Shizuku is required,
# and the internal-display/app-handle interaction are all in desktop/README.md.
# Guarded: the two APKs are third-party release binaries and are .gitignore'd, so a fresh clone of
# this tree does NOT contain them. Without this guard the build fails on a missing source for anyone
# who clones and builds. Drop the two APKs into desktop/ (checksums and download links are in
# desktop/README.md) and they are picked up automatically.
# REMOVED FROM THE ROM 2026-08-27. Two reasons, both decisive:
#
# 1. Preinstalling these is BROKEN BY DESIGN. Both are upstream release APKs with
#    compressed lib/arm64-v8a/*.so and extractNativeLibs=true. That layout is only
#    correct for an app the platform INSTALLS -- it unpacks the libs to
#    /data/app/<pkg>/lib/arm64. A preinstalled app on a read-only partition never gets
#    that step, and the build did not place the libs at <appdir>/lib/arm64 either, so
#    /product/app/Shizuku/ held only the APK. Result, reproduced on hardware:
#      FATAL EXCEPTION: java.lang.UnsatisfiedLinkError:
#      dlopen failed: library "libadb.so" not found
#          at moe.shizuku.manager.ShizukuApplication.<clinit>
#    i.e. Shizuku crashed instantly for every user (XDA #12, Haldi4803). The
#    skip_preprocessed_apk_checks:true in desktop/Android.bp had suppressed exactly the
#    check that catches compressed JNI libs in a preprocessed APK.
#    The same APK installed normally works: verified, MainActivity resumed, 0 crashes.
#
# 2. Shizuku is a privilege broker. Baking it into the system partition makes it
#    un-uninstallable and is the user's decision to make, not ours.
#
# Both are shipped as optional downloads instead. MagicDesk depends on Shizuku as its
# only privilege transport, so they leave together.

# OpenEUICC — privileged eSIM LPA (Local Profile Assistant) for the internal
# removable eUICC. Builds from packages/apps/OpenEUICC as a platform-signed
# system_ext priv-app (+ liblpac-jni native + privapp permission whitelist).
PRODUCT_PACKAGES += \
    OpenEUICC

# Bluetooth classic profiles. The closure build shipped ONLY the LE-Audio
# profile defaults; every classic profile (A2DP source, HFP AG, AVRCP, GATT,
# HID, PAN, MAP, PBAP, OPP) was unset, so no classic profile service started
# -> BT bonded but produced no audio on ANY device. Root-caused 2026-06-08 via
# an empty "Enabled Profile Services" list (the real fix; the earlier
# a2dp_offload.disabled idea was a red herring -- offload works fine).
PRODUCT_PRODUCT_PROPERTIES += \
    persist.sys.dt2w.enabled=1 \
    bluetooth.profile.a2dp.source.enabled=true \
    bluetooth.profile.hfp.ag.enabled=true \
    bluetooth.profile.avrcp.target.enabled=true \
    bluetooth.profile.gatt.enabled=true \
    bluetooth.profile.hid.host.enabled=true \
    bluetooth.profile.hid.device.enabled=true \
    bluetooth.profile.pan.nap.enabled=true \
    bluetooth.profile.pan.panu.enabled=true \
    bluetooth.profile.map.server.enabled=true \
    bluetooth.profile.pbap.server.enabled=true \
    bluetooth.profile.opp.enabled=true \
    bluetooth.profile.sap.server.enabled=true

# LE Audio (LC3) dynamic switcher. Dual-mode buds (e.g. Galaxy Buds 3 Pro) are
# LE-Audio capable (BAP unicast client enabled, controller does ISO/CIS) but
# stream classic A2DP because the dynamic A2DP<->LE-Audio switcher is gated on
# this read-only prop, which is unset by default (per-device LE_AUDIO policy
# stays forbidden without it). Enabling it surfaces the switcher (Settings /
# Developer options) so dual-mode devices can use LE Audio. ro.bluetooth.* is
# readable cross-partition, so product/etc/build.prop reaches the stock-vendor BT
# stack (same cross-partition pattern as the DT2W default prop above).
PRODUCT_PRODUCT_PROPERTIES += \
    ro.bluetooth.leaudio_switcher.supported=true

# VoLTE/VT enablement. This network is VoLTE-only (no 2G/3G CS fallback) and the
# modem+network are VoLTE-capable (mVopsSupport=2, VOICE available over LTE), but
# the framework gates IMS voice off: dumpsys carrier_config shows
# carrier_volte_available_bool=false for the carrier, so calls connect with NO
# voice media bearer (CallQuality numRtpPackets{Transmitted,Received}=0) -> no
# audio either way. These debug-override props bypass the framework VoLTE/VT/WFC
# availability check (the standard LineageOS fix when the modem supports VoLTE but
# the generic carrier config doesn't enable it). See volte_call_audio_2026-06-16.
# Validate end-to-end after a clean permissive boot: a call should show RTP>0 +
# audio. If insufficient, add a CarrierConfig overlay (carrier_volte_available_bool=true).
PRODUCT_PRODUCT_PROPERTIES += \
    persist.dbg.volte_avail_ovr=1 \
    persist.dbg.vt_avail_ovr=1 \
    persist.dbg.wfc_avail_ovr=1

# QTI IMS / VoLTE framework stack (the org.codeaurora.ims ImsService + QTI
# telephony jars + apps, grafted from the EA stock dump). The props above are
# necessary-but-insufficient prereqs — without an ImsService the framework has
# nothing to bind. See ims.mk + volte_call_audio_2026-06-16.
include $(LOCAL_PATH)/ims.mk

# The three microG APKs are upstream release binaries and are .gitignore'd (GmsCore alone is
# 108 MB, over GitHub's file limit), so a fresh clone does NOT contain them. microg/Android.bp
# references them unconditionally, so a missing file fails Soong for EVERY variant with an
# opaque "source path does not exist" - fail here first, with instructions.
ifeq ($(wildcard $(LOCAL_PATH)/microg/prebuilt/MicroGGmsCore.apk),)
$(error NX809J: microg/prebuilt/*.apk absent. Download the three microG APKs listed in microg/prebuilt/README.md (sha256 pinned there) before building any variant)
endif

# QTI AIDL audio HAL vendor-parameter extension — THE fix for silent VoLTE audio.
# On Android 16 the AIDL audio HAL no longer parses legacy AudioManager.setParameters
# KV strings itself: AOSP DeviceHalAidl delegates the raw "call_state=2;vsid=...;
# call_type=LTE" string to the system_ext service android.media.audio.IHalAdapterVendorExtension/
# default, which splits it into discrete VendorParameters and forwards them to the QTI
# HAL's onSetTelephonyParameters -> Telephony::reconfigure -> updateCalls -> opens the
# modem voice session (SessionAlsaVoice / PAL_STREAM_VOICE_CALL type 14). Without this
# service bound, DeviceHalAidl "fails open" — logs the string but DROPS the params — so
# the per-VSID voice session never leaves IN_ACTIVE ("updateCalls CallState: Default")
# and the modem-anchored voice is never bridged to the codec => silence both ways, even
# though signaling/VoLTE/codec are all correct. qtiaudiohalvendorextn ships the parser
# (vendor/qcom/opensource/commonsys/audio/hal_adapter); DeviceHalAidl only binds it when
# ro.audio.ihaladaptervendorextension_enabled=true. Stock RedMagicOS sets both; the
# NX809J tree never inherited audio_system_product.mk. See volte_call_audio_2026-06-16.
PRODUCT_PACKAGES += \
    qtiaudiohalvendorextn

PRODUCT_SYSTEM_EXT_PROPERTIES += \
    ro.audio.ihaladaptervendorextension_enabled=true

# Vendor ueventd.rc — the built vendor.img shipped WITHOUT /vendor/etc/ueventd.rc
# because closure.py excludes it (assuming source init provides it, but source only
# generates /system/etc/ueventd.rc). Without it, 268 vendor device-node permission
# rules are absent — critically the UFS RPMB BSG node (/dev/0:0:0:49476 0600 system
# system), so qseecomd couldn't reach RPMB -> exited status 255 -> keymint had no
# secure storage -> never registered IKeyMintDevice -> keystore2 hung vold -> boot froze.
# (Root-caused 2026-07-07 via verbose pstore; the file is the stock vendor's, byte-for-byte.)
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/rootdir/vendor/etc/ueventd.rc:$(TARGET_COPY_OUT_VENDOR)/etc/ueventd.rc

# --- boot diagnostics: DELIBERATELY NOT SHIPPED (removed 2026-08-22) ---
# bootmark/00-bootmark.rc is kept in the tree for future boot debugging but is no longer copied
# into the image. It said "remove before ship" and never was, and it shipped in every public build
# with real consequences, all verified live on the 20260820 release:
#   * services `klog` and `logcatcap` crash-loop forever -- exit 127 and 1 respectively, restarted
#     ~13x per minute. They produced ~80% of all logcat output (3003 of 3767 lines in 60 s), which
#     is why the log buffer only held about a minute and users' bug reports kept missing the moment
#     of failure.
#   * that respawn loop sets sys.init.updatable_crashing=1, so Android's crash-recovery
#     (flags_health_check) runs continuously. Repeated escalation of that path can end in a reboot.
#   * `write /proc/sys/kernel/dmesg_restrict 0` lowered a kernel security setting for every user.
#   * `setprop persist.sys.usb.config adb` forced USB into adb mode persistently on every boot.
# To debug a boot hang again, add these two lines back for that build only.
#PRODUCT_COPY_FILES += \
#    $(LOCAL_PATH)/bootmark/00-bootmark.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/00-bootmark.rc \
#    $(LOCAL_PATH)/bootmark/bootmark_byte:$(TARGET_COPY_OUT_SYSTEM)/etc/bootmark_byte

# Firmware-partition mount-point dirs (firmware_mnt/bt_firmware/soccp_firmware): created
# via BOARD_*_EXTRA_DIRS / soong fsgen (see BoardConfig.mk) — NOT PRODUCT_COPY_FILES, since
# soong rejects files inside a mount point. Root-caused 2026-07-07: built vendor lacked
# these dirs -> firmware partitions couldn't mount -> WCN firmware (amss20.bin/soccp.mbn)
# unreachable -> cnss_recovery_handler PANIC at t=69s.

# IR remote: the HAL ships in the stock vendor (vendor.ir-default +
# consumerir.zte.so) and the device already reports the consumerir feature, so
# the copied permission below is belt-and-suspenders. The user-facing app
# (com.zte.remotecontroller / InfraredCoolControl) IS now bundled via ir/ir.mk
# — ported from CN firmware, per github.com/IronShing/nx809j-ir-port. NOTE for a
# public release: it's a proprietary ZTE/KooKong app; bundling is the user's call.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/prebuilt/etc/permissions/android.hardware.consumerir.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/android.hardware.consumerir.xml

# Desktop mode: declare freeform window management so Android 16 desktop
# windowing is fully enabled. Three pieces must all be present:
#   1. the freeform_window_management feature (PackageManager) — below;
#   2. the enable_desktop_windowing_mode / enable_desktop_mode_through_dev_option
#      aconfig flags (ENABLED in the build, verified baked into system aconfig_flags.pb);
#   3. the framework-res config overlay below — config_canInternalDisplayHostDesktops
#      and config_isDesktopModeDevOptionSupported default to FALSE in AOSP, so without
#      this overlay desktop mode is unavailable on the internal display / dev option.
# The overlay MUST be registered as a static PRODUCT_PACKAGE_OVERLAYS (baked into
# framework-res.apk, same mechanism vendor/lineage/overlay/common uses to set
# config_isDesktopModeSupported=true). It was previously authored as a never-built RRO
# (not in PRODUCT_PACKAGES) → the two extra bools never applied → desktop mode broken.
PRODUCT_PACKAGE_OVERLAYS += $(LOCAL_PATH)/overlay

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.software.freeform_window_management.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/android.software.freeform_window_management.xml

# Declare the fingerprint feature so PackageManager exposes FEATURE_FINGERPRINT
# and SystemServer starts FingerprintService. Shipped via system_ext because the
# vendor partition is spliced from stock and the unit we ship (h1_build/vendor_a.img)
# carries only qti_fingerprint_interface.xml, not android.hardware.fingerprint.xml.
# Harmless duplicate if a future vendor also declares it (PackageManager dedupes).
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.fingerprint.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/android.hardware.fingerprint.xml

# Disable the crash-looping modem-subsystem daemons (init-ssdaemon_vendor,
# qti-ssdaemon/msdaemon; libss-qti dlopen fails — RIL unaffected). Was
# staging-injected during bring-up; now in source for permanence.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/prebuilt/etc/init/disable-ssdaemon.rc:$(TARGET_COPY_OUT_PRODUCT)/etc/init/disable-ssdaemon.rc

# Disable two crash-looping stock-vendor HALs that drive a RescueParty flag-reset
# storm + battery drain: the eSE secure_element HAL (status=-3, can't init on this
# port; also flips sys.init.updatable_crashing on a loop) and the unused China IFAA
# biometric-pay HAL. /product/etc/init is parsed after /vendor so the stops win.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/prebuilt/etc/init/disable-crashloop-hals.rc:$(TARGET_COPY_OUT_PRODUCT)/etc/init/disable-crashloop-hals.rc

# TxPwrAdmin (vendor.qti.data.txpwradmin) crashes on user-switch: its non-singleUser
# components get spawned for secondary users, but it expects a single system-user
# instance in the shared .qms process. The APK is ZTE-platform-signed (shared UID
# with the QTI telephony suite) so we can't edit its manifest. Instead confine it to
# the SYSTEM user via SystemConfig install-in-user-type (whitelist mode has ENFORCE).
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/prebuilt/etc/sysconfig/txpwradmin-system-user.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/txpwradmin-system-user.xml

# Goodix UDFPS: make fingerprint survive a /data wipe. What a wipe actually destroys is
# the virtual-HAL config props (they live in /data/property/persistent_properties);
# restore-fp-cal.rc re-asserts them in post-fs-data, before the Goodix HAL starts.
#
# We deliberately ship NO calibration. The per-unit cal is
# /mnt/vendor/persist/goodix/cali_data_0.so — factory-programmed, per-device, not wiped by
# a factory reset and not a partition we flash, so every phone already has its own. The
# /data/vendor/goodix/cali_*.so are runtime base images the HAL regenerates by itself
# (verified on hardware 2026-08-06: emptied that dir, HAL rebuilt it, enroll+unlock work).
# Older builds copied this build machine's copies of those files onto every phone, which is
# exactly why UDFPS only worked here; fp-cal-purge.sh removes them once on upgrade.
# See memory nx809j_fp_persist_partition_cal.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/prebuilt/etc/init/restore-fp-cal.rc:$(TARGET_COPY_OUT_PRODUCT)/etc/init/restore-fp-cal.rc \
    $(LOCAL_PATH)/prebuilt/etc/goodix/fp-cal-purge.sh:$(TARGET_COPY_OUT_PRODUCT)/etc/goodix/fp-cal-purge.sh

# Double-tap-to-wake (WORKING). The ZTE/Synaptics zte_tpd driver detects the
# double-tap in low-power gesture mode and, instead of an input KEY_WAKEUP, fires
# a "double_tap=true" netlink uevent + holds the SoC awake ~2s (pm_wakeup_ws_event).
# Stock RedMagicOS had a userspace consumer of that uevent; LOS didn't. dt2w_uewake
# is that consumer: it arms /proc/touchscreen/wake_gesture, listens on the netlink
# socket, and on "double_tap=true" injects KEY_WAKEUP via a uinput device
# (dt2w_uewake.kl flags it WAKE) so the framework wakes the display.
# Gated on persist.sys.dt2w.enabled, defaulted ON via PRODUCT_PRODUCT_PROPERTIES
# above (daemon auto-starts at boot). To disable (save standby battery):
# setprop persist.sys.dt2w.enabled 0 (persists; disarms wake_gesture too).
# ENFORCING split: dt2w_uewake binary -> /system_ext coredomain (netlink+uinput
# only); its SERVICE block ships in dt2w_uewake.rc (the binary's init_rc, also
# /system_ext) so init's coredomain type_transition fires. The syna_proc gesture-
# node writes live in dt2w_uewake_arm.rc -> /odm (vendor_init). The daemon pulses
# sys.dt2w.arm to request the arm. (Binary's init_rc auto-installs dt2w_uewake.rc,
# so only the binary + the odm arm .rc need listing here.)
PRODUCT_PACKAGES += \
    dt2w_uewake \
    dt2w_uewake_arm.rc

# RKP toggle: init-domain property bridge. The RedMagic Control switch writes
# persist.sys.rm.rkp and these actions do the remote_provisioning.* setprops, which the app
# itself is neverallowed to do (property.te). See rkp/rkp.rc.
PRODUCT_PACKAGES += \
    rkp.rc

# gameperfd (vendor: true -> out/.../vendor/bin; sign_a17_release.sh bakes that file into the
# hybrid vendor). It was NOT listed here, so target-files-package never rebuilt it: the 20260829
# and 20260831/0901 releases shipped a binary compiled BEFORE the gamecool/fan-auto rewrite of
# the same day (verified 2026-09-02: `strings /vendor/bin/gameperfd` had no "gamecool"), which is
# why "Fan speed while gaming" and "Cool while gaming" did nothing and the fan ran at 5 in every
# Performance-mode game (XDA Bobo9996). Listing it makes every build compile the current source.
PRODUCT_PACKAGES += \
    gameperfd
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/dt2w/dt2w_uewake.kl:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/keylayout/dt2w_uewake.kl

# --- Magic Slider bridge (slider_uewake) ---
# The physical slider reports EV_SW / SW_PEN_INSERTED on the gpio-keys_nubia input device.
# On stock, ZTE's SlideKeysCtrl (inside their system server) consumes it; an AOSP-based ROM
# has no such component, so the slider does nothing (reported on XDA). slider_uewake is a
# system_ext coredomain daemon that watches the switch and publishes sys.rm.slider.state /
# .event; RedMagicControl decides the action, so no root and no vendor access is needed.
# NB: the hardware doc says /dev/input/event1 — that is wrong here (event1 is the shoulder
# SAR sensor, the slider is event3), so the daemon scans by device name instead.
PRODUCT_PACKAGES += \
    slider_uewake

# PRODUCT_PRODUCT_PROPERTIES, not PRODUCT_PROPERTY_OVERRIDES: the latter does not emit
# `persist.`-prefixed properties into any build.prop, so the gate below was never set and
# slider_uewake -- which is `disabled` and started only `on property:...enabled=1` -- never
# started at all. Verified on hardware 2026-08-20: getprop returned empty and the service was
# absent. dt2w defaults its own gate the same way (see persist.sys.dt2w.enabled above).
PRODUCT_PRODUCT_PROPERTIES += \
    persist.sys.rm.slider.enabled=1

# --- Deep-sleep ultrasonic-FP wake+unlock (fp_uewake) ---
# The ultrasonic UDFPS works screen-on; in deep sleep the touch driver reports an
# FP-area finger-down as a kobject uevent (aod_areameet_down=true) instead of an
# input event. fp_uewake (system_ext coredomain, netlink+uinput) injects
# KEY_WAKEUP + a synthetic FP-area touch so the UDFPS scans the held finger and
# unlocks — place finger on the dark screen -> wake+unlock, like stock. Same
# enforcing split as dt2w_uewake: syna_proc arming via odm fp_uewake_arm.rc.
PRODUCT_PACKAGES += \
    fp_uewake \
    fp_uewake_arm.rc

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/fp_uewake/fp_uewake.kl:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/keylayout/fp_uewake.kl

PRODUCT_PRODUCT_PROPERTIES += \
    persist.sys.fp_wake.enabled=1

# Firmware (vendor blobs installed via PRODUCT_COPY_FILES in vendor mk)
$(call inherit-product-if-exists, vendor/nubia/NX809J/NX809J-vendor.mk)

# Boot control HAL — needed in recovery for OTA sideload (update_engine)
PRODUCT_PACKAGES += \
    android.hardware.boot-service.default_recovery

# Init
PRODUCT_PACKAGES += \
    fstab.qcom \
    init.NX809J.rc

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/init.recovery.qcom.rc:$(TARGET_COPY_OUT_RECOVERY)/root/init.recovery.qcom.rc

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/rootdir/etc/fstab.qcom:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/fstab.qcom

# Soong-bypass installs for orphan AIDL prebuilts.
# These are vendor .so files whose AIDL interface version is no longer
# built by AOSP source (frozen at an older version), but vendor consumer
# blobs DT_NEED them at process load. Listing them in proprietary-files.txt
# would create a Soong PART conflict (source declares the namespace name
# even when it can't build the version). PRODUCT_COPY_FILES is a Make-
# level mechanism that bypasses Soong's module system entirely.
# BUILD_BROKEN_ELF_PREBUILT_PRODUCT_COPY_FILES := true (BoardConfig.mk)
# permits ELFs in PRODUCT_COPY_FILES.
#
# - sharedsecret-V2: source frozen at V1; libspukeymint.so DT_NEEDs V2
#   and would fail at dlopen otherwise. Source: verify_aidl_versions.py
#   KEEP entry, .aidl_verify.txt.
# - libNubiaImageAlgorithmVD: Nubia proprietary camera/image algorithm
#   library. Has no AOSP source counterpart. cc_prebuilt_library_shared
#   can't be emitted because the blob's DT_NEEDED includes libskia,
#   which only has a system variant in AOSP source — Soong fails with
#   "missing variant" when trying to build a vendor variant. Bypassing
#   Soong via PRODUCT_COPY_FILES installs the .so directly to
#   /vendor/lib64/, where any vendor process that dlopens it by name
#   will find it. (BUILD_BROKEN_ELF_PREBUILT_PRODUCT_COPY_FILES allows
#   ELFs in PRODUCT_COPY_FILES.)
PRODUCT_COPY_FILES += \
    vendor/nubia/NX809J/proprietary/vendor/lib64/libNubiaImageAlgorithmVD.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libNubiaImageAlgorithmVD.so
# Note: sharedsecret-V2-ndk.so + libhapticgenerator.so were PCF-installed
# earlier as workarounds for Soong analysis errors. Both are now
# resolved via Soong (sharedsecret-V2 auto-generated by libspukeymint
# transitive AIDL closure; libhapticgenerator builds from
# frameworks/av/media/libeffects/hapticgenerator/). PCF copies removed
# to avoid kati duplicate-target collisions.

# SPU TEE keymint cluster — PCF bypass.
# See closure.py EXCLUDE_PATHS comment block for the full rationale.
# Soong rejects the cluster because libspukeymintprovision (statically
# linked into keymint-service-spu-qti) DT_NEEDs keymint-V2-ndk while
# the binary also DT_NEEDs keymint-V4-ndk → multi-version conflict.
# Runtime resolution from /system/lib64/ handles V2 and V4 independently.
PRODUCT_COPY_FILES += \
    vendor/nubia/NX809J/proprietary/vendor/lib64/libspukeymint.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libspukeymint.so \
    vendor/nubia/NX809J/proprietary/vendor/lib64/libspukeymintdeviceutils.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libspukeymintdeviceutils.so \
    vendor/nubia/NX809J/proprietary/vendor/lib64/libspukeymintprovision.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libspukeymintprovision.so \
    vendor/nubia/NX809J/proprietary/vendor/lib64/libspukeymintutils.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libspukeymintutils.so \
    vendor/nubia/NX809J/proprietary/vendor/lib64/hw/libspuqtigatekeeper.so:$(TARGET_COPY_OUT_VENDOR)/lib64/hw/libspuqtigatekeeper.so \
    vendor/nubia/NX809J/proprietary/vendor/bin/spu_install_keybox:$(TARGET_COPY_OUT_VENDOR)/bin/spu_install_keybox \
    vendor/nubia/NX809J/proprietary/vendor/bin/hw/android.hardware.gatekeeper-service-spu-qti:$(TARGET_COPY_OUT_VENDOR)/bin/hw/android.hardware.gatekeeper-service-spu-qti \
    vendor/nubia/NX809J/proprietary/vendor/bin/hw/android.hardware.security.keymint-service-spu-qti:$(TARGET_COPY_OUT_VENDOR)/bin/hw/android.hardware.security.keymint-service-spu-qti \
    vendor/nubia/NX809J/proprietary/vendor/bin/hw/android.hardware.weaver-service-spu-qti:$(TARGET_COPY_OUT_VENDOR)/bin/hw/android.hardware.weaver-service-spu-qti

# Camera node plugins with multi-version DT_NEEDED — PCF bypass.
# com.qti.node.dewarp.so has BOTH graphics.allocator-V1 and -V2 NEEDED
# entries in a single binary; Soong rejects. Camera HAL loads these
# plugins by name via dlopen at runtime, no Soong link needed.
PRODUCT_COPY_FILES += \
    vendor/nubia/NX809J/proprietary/vendor/lib64/camera/components/com.qti.node.dewarp.so:$(TARGET_COPY_OUT_VENDOR)/lib64/camera/components/com.qti.node.dewarp.so

# iter 224: Critical vendor HAL binaries missing from proprietary-files.txt.
# These were excluded by closure.py due to Soong analysis conflicts but are
# required at runtime. Without these: no display, no boot control (watchdog
# reboot), no USB (no ADB), no audio, no sensors.
# Identified via pstore crash analysis: phone booted 228s then watchdog
# rebooted because sys.boot_completed never set.
PRODUCT_COPY_FILES += \
    vendor/nubia/NX809J/proprietary/vendor/bin/hw/android.hardware.boot-service.qti:$(TARGET_COPY_OUT_VENDOR)/bin/hw/android.hardware.boot-service.qti \
    vendor/nubia/NX809J/proprietary/vendor/etc/init/android.hardware.boot-service.qti.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/android.hardware.boot-service.qti.rc \
    vendor/nubia/NX809J/proprietary/vendor/bin/hw/android.hardware.thermal-service.qti:$(TARGET_COPY_OUT_VENDOR)/bin/hw/android.hardware.thermal-service.qti \
    vendor/nubia/NX809J/proprietary/vendor/etc/init/android.hardware.thermal-service.qti.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/android.hardware.thermal-service.qti.rc

# PCF bypass for firmware-style trees that hit soong_filesystem_creator
# "Path is outside directory" or are silently dropped by extract-utils.
# See closure.py PCF_BYPASS_PATHS for the per-prefix rationale.
# firmware_pcf.mk is auto-generated; re-run closure.py to refresh.
include $(LOCAL_PATH)/firmware_pcf.mk

# Phase A native-enforcing: build the 9 QTI HAL services into vendor.img.
include $(LOCAL_PATH)/vendor_hals.mk
include $(LOCAL_PATH)/vendor_source_extras.mk


# Overlays — see the desktop-mode block above. The framework-res overlay is now a
# static PRODUCT_PACKAGE_OVERLAYS (baked into framework-res.apk in system), replacing
# the former FrameworksResNX809J RRO, which was device_specific → /odm/overlay and so
# never reached the device (we ship a stock-derived odm). Static overlay = reliable.

# Partitions
PRODUCT_BUILD_SUPER_PARTITION := true

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    hardware/qcom-caf/sm8750 \
    hardware/qcom-caf/wlan \
    hardware/qcom-caf/wlan/qcwcn \
    vendor/qcom/opensource/commonsys-intf/display \
    external/OpenCL-ICD-Loader \
    hardware/qcom-caf/common/libqti-perfd-client \
    vendor/qcom/opensource/dataservices \
    vendor/nubia/NX809J \
    $(LOCAL_PATH)

# WiFi/qcwcn namespace import — required by hostapd and wpa_supplicant which
# depend on lib_driver_cmd_qcwcn (defined at
# hardware/qcom-caf/wlan/qcwcn/wpa_supplicant_8_lib/Android.bp:38).
# Soong namespaces are NOT recursive — importing the parent hardware/qcom-caf/wlan
# doesn't grant access to the qcwcn child namespace, even though qcwcn's
# Android.bp itself imports the parent.
#
# Verified iter 189: only one child soong_namespace exists under
# hardware/qcom-caf/wlan/ (qcwcn). No siblings to bulk-add. dodge sm8750-common
# gets this namespace from somewhere in its inheritance chain (NOT from
# hardware/qcom-caf/common/common.mk, which has zero PRODUCT_SOONG_NAMESPACES
# declarations — verified iter 189). We add it directly here.
#
# This is the downstream consequence of iter-188's wifi PRODUCT_PACKAGES
# additions. Adding hostapd/wpa_supplicant to the build graph surfaced this
# namespace gap.

# Update engine
PRODUCT_PACKAGES += \
    update_engine \
    update_engine_sideload \
    update_verifier

# VINTF compatibility matrix module
# The vintf_data{type:"device_cm"} producer is defined at
# system/libhidl/vintfdata/Android.bp:19-24, fed our static stock matrix via
# DEVICE_MATRIX_FILE in BoardConfig.mk. The producer module is named
# vendor_compatibility_matrix.xml (Soong module name, not file name).
#
# AOSP base_vendor.mk:112 lists this module in PRODUCT_PACKAGES, which is how
# devices that inherit base_vendor.mk get the matrix automatically. We don't
# inherit base_vendor.mk (it would re-add ~50 modules we've explicitly excluded
# via the closure approach), so we add this single module surgically.
#
# Without this line, the soong build rule exists, the install rule exists, the
# static input file exists, but no goal target visits the edge — ninja never
# produces vendor/etc/vintf/compatibility_matrix.xml, and checkvintf halts the
# build with NAME_NOT_FOUND. Took four iters (173, 181, 183, 184) to find this.
PRODUCT_PACKAGES += \
    vendor_compatibility_matrix.xml

# Framework VINTF modules — symmetric to vendor_compatibility_matrix.xml above.
# AOSP base_system.mk:414 lists these in PRODUCT_PACKAGES; devices that inherit
# base_system get them automatically. We don't inherit base_system (closure
# approach), so add them surgically.
#
# Without these, the OTA generator's checkvintf step fails at iter 212 with:
#   "No framework manifest file from device or from update package"
#   "No framework compatibility matrix files under /system/etc/vintf/"
# Because the SYSTEM staging dir has no etc/vintf/ contents at all.
#
# Modules:
#   system_manifest.xml — vintf_data{type:"system_manifest"} from
#     system/libhidl/vintfdata/Android.bp:26-30. Generates the framework
#     HAL manifest at /system/etc/vintf/manifest.xml
#   system_compatibility_matrix.xml — base_system.mk standard. Generates
#     the framework compatibility matrix at /system/etc/vintf/compatibility_matrix.xml
#     (assembled from DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE in BoardConfig.mk)
#
# Symmetric to iter 184's vendor_compatibility_matrix.xml fix.
PRODUCT_PACKAGES += \
    system_manifest.xml \
    system_compatibility_matrix.xml

# WiFi packages — explicit closure inclusion to fix iter-187 libwifi-hal-qcom drop
# Sourced from LineageOS device/oneplus/sm8750-common/common.mk at lineage-23.2.
# The libwifi-hal-qcom entry is the critical one; without it, the source-side
# cc_library compiles a CFI variant that doesn't match our prebuilt's plain variant,
# and Soong silently drops the dep from hal_proxy_daemon's resolved shared_libs.
# Adding to PRODUCT_PACKAGES forces the module into the goal closure (same shape
# as iter-184's vendor_compatibility_matrix.xml fix).
PRODUCT_PACKAGES += \
    android.hardware.wifi-service \
    hostapd \
    libwifi-hal-ctrl \
    libwifi-hal-qcom \
    wpa_supplicant \
    wpa_supplicant.conf

# Wi-Fi capability RRO. The stock vendor overlays (/vendor/overlay/WifiResMainlineTarget*.apk)
# target com.google.android.wifi.resources — the GMS wifi module — so they never apply on our
# AOSP wifi module and every device Wi-Fi capability falls back to the AOSP default. Most
# visibly config_wifi5ghzSupport, which defaults to false and makes the 5 GHz hotspot
# unselectable (picking it restarts the AP back onto 2.4 GHz). See
# rro_overlays/WifiOverlay/Android.bp.
PRODUCT_PACKAGES += \
    NX809JWifiOverlay

# 16 KB page size check bypass for prebuilt libraries
#
# Android 16 (Baklava) introduced PRODUCT_CHECK_PREBUILT_MAX_PAGE_SIZE which
# validates that prebuilt vendor libraries have load segments aligned to >=
# 16 KB. Some prebuilts in upstream AOSP (notably prebuilts/misc/protobuf_
# vendorcompat/) were compiled with 4 KB max-page-size and haven't been
# updated for the 16 KB requirement.
#
# The check is advisory: kernels handle 4 KB-aligned segments within 16 KB
# regions transparently. Bypassing the check has no functional impact at
# runtime; it only suppresses build-time warnings about inefficient memory
# packing on 16 KB page systems.
#
# AOSP documents this bypass directly in check_elf_file's error message:
#   "Device mk: PRODUCT_CHECK_PREBUILT_MAX_PAGE_SIZE := false"
#
# Affected prebuilts in our build (verified iter 205):
#   prebuilts/misc/protobuf_vendorcompat/arm64/libprotobuf-cpp-lite-21.12.so
#   prebuilts/misc/protobuf_vendorcompat/arm64/libprotobuf-cpp-full-21.12.so
#   plus 6 more older / 32-bit variants in same dir (8 total potentially affected)
#
# Dodge sm8750-common doesn't trip this check (verified iter 205) — likely
# because their PRODUCT_PACKAGES doesn't pull in libprotobuf-cpp-*-vendorcompat.
# We pull them in via some closure-related package; identifying the parent
# is post-build investigation work, not blocking. Bypass is the documented
# AOSP mechanism regardless.
PRODUCT_CHECK_PREBUILT_MAX_PAGE_SIZE := false

# Battery charging control -> Settings > Battery > Charging control.
# The stock vendor charger holds the battery at 100%. Wire the LineageOS health
# HAL's Toggle provider to the qcom-battery charge enable/disable node so the user
# can cap the charge level from Settings. The framework shows the toggle whenever
# the vendor.lineage.health IChargingControl service is declared (no extra gate).
#
# The HAL is built to ODM (it is patched device_specific in
# hardware/lineage/interfaces/health/aidl/default — vendor:true -> device_specific
# plus its .rc exec path /vendor -> /odm) because we ride the STOCK /vendor
# partition and never flash a built vendor.img; odm is the partition we ship.
PRODUCT_PACKAGES += \
    vendor.lineage.health-service.default

$(call soong_config_set,lineage_health,charging_control_charging_path,/sys/class/qcom-battery/charging_enabled)
$(call soong_config_set,lineage_health,charging_control_charging_enabled,1)
$(call soong_config_set,lineage_health,charging_control_charging_disabled,0)
# Charge-limit % slider + enforcement. IMPORTANT: this device's charger FIRMWARE
# IGNORES the kernel charge_control_{start,end}_threshold nodes (writable but cosmetic
# — verified: battery 95%, end_threshold=70, even charge_control_en=1, still CHARGING).
# So the HAL's threshold-based LIMIT mode does NOT work here. The only mechanism that
# actually stops charging is the charging_enabled TOGGLE (verified: 0 -> status DISCHARGING).
# Therefore: advertise TOGGLE only (supports_toggle). The framework's Toggle ccprovider
# handles MODE_LIMIT by polling battery level and toggling charging_enabled around the
# target % (Toggle.onBatteryChanged -> setChargingEnabled). The % slider still appears
# because allowFineGrainedSettings() needs TOGGLE *or* LIMIT. When the limit holds,
# status goes plugged+discharging -> SystemUI battery-defender (shield) engages.
# (supports_limit + threshold paths were tried and DROPPED: Limit ccprovider has priority
# over Toggle but only writes the firmware-ignored thresholds -> slider showed but limit
# never enforced + no shield.)
$(call soong_config_set_bool,lineage_health,charging_control_supports_toggle,true)

# Deferred SELinux enforcing: flip to Enforcing at boot_completed (boots permissive
# so the init-domain security HALs connect, then enforces). See enforcing/README.md.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/rootdir/etc/init/nx809j-enforce.rc:$(TARGET_COPY_OUT_PRODUCT)/etc/init/nx809j-enforce.rc

# Do not enforce kernel VINTF requirements at check_vintf time. We ship a PREBUILT
# GKI kernel (WildKernels OP-WILD, 6.12.23) we don't compile, and its config has
# CONFIG_SYSVIPC=y, which the Android 16 framework matrix (FCM 202504) requires =n.
# We can't reconfigure a prebuilt kernel, so drop the --kernel arg from checkvintf
# (build/make/core/Makefile:5634). Without this, `m dist` fails at 99% in the
# check_vintf_compatible packaging step:
#   "No compatible kernel requirement found (kernel FCM version = 202504)
#    ... For config CONFIG_SYSVIPC, value = y but required n"
# Only the dist/OTA path runs this check (incremental image builds don't), so it
# surfaces only on the release build. Runtime VINTF is unaffected.
PRODUCT_OTA_ENFORCE_VINTF_KERNEL_REQUIREMENTS := false

# NX809J: cap AudioService policy-service wait so AudioService.<init> doesn't
# block system_server's main thread ~65s (13x 5s) when the QTI audio-policy
# service is slow/absent -> Watchdog kill. Read by AudioSystem.cpp.
PRODUCT_PROPERTY_OVERRIDES += \
    audio.service.client_wait_ms=500

# --- VoIP Call Recorder (privileged app: auto-records allow-listed VoIP calls,
# WhatsApp by default, both sides, via the framework capture path — relies on the
# stock ZTE audio HAL's ro.vendor.feature.zte_feature_system_record_voip_enabled=true) ---
PRODUCT_PACKAGES += \
    VoipRecorder
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/apps/VoipRecorder/privapp_permissions_com.nx809j.voiprecorder.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp_permissions_com.nx809j.voiprecorder.xml

# --- RedMagic Control app (Settings screen + QS tiles: Loudness / FP Wake / DT2W
# / Cooling Fan (5 levels) / Liquid Cooling (3 levels)) ---
PRODUCT_PACKAGES += \
    RedMagicControl

# RedMagicControl is privileged (/system_ext/priv-app), so its signature|privileged permissions must
# be allowlisted as well as signature-granted. Without this, PackageManagerService.systemReady()
# throws under ro.control_privapp_permissions=enforce and the device never finishes booting -- this
# is what broke the LineageOS builds. See permissions/privapp-permissions-com.nubia.rmcontrol.xml.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/permissions/privapp-permissions-com.nubia.rmcontrol.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-com.nubia.rmcontrol.xml

# Fan (5) + liquid-cooling (3) level triggers -> /odm/etc/init (vendor_init writes
# the vendor fan/micropump nodes; tiles set persist.sys.{fan,cooling}.level).
# Same enforcing split as dt2w_uewake_arm.rc.
PRODUCT_PACKAGES += \
    redmagic_hw_arm.rc

PRODUCT_PACKAGES += \
    loudness

# hwcontrol daemon: applies RedMagicControl RGB / edge-reject / triggers / haptics
# / auto-fan settings (persist.sys.rm.*) to the hardware nodes it can reach.
PRODUCT_PACKAGES += \
    hwcontrol

$(call inherit-product-if-exists, device/nubia/NX809J/audio/viper4android/viper4android.mk)
$(call inherit-product-if-exists, device/nubia/NX809J/ir/ir.mk)

PRODUCT_COPY_FILES += \

# --- LOCAL TEST BUILD ONLY: pre-authorised adb.  NEVER SHIP THIS. ---
# Baking a developer's adb public key into the image means every device running
# that image trusts that key -- a remote-access backdoor in a public ROM. This is
# gated so it can only ever be enabled deliberately, for a build that is flashed
# and tested locally and then thrown away:
#     NX809J_TEST_ADB=true m ...
# /adb_keys is already a symlink to /product/etc/security/adb_keys (which nothing
# creates), so the key must land in PRODUCT for the symlink to resolve. Also force
# usb into adb mode, because a full flash erases /data, taking both
# /data/misc/adb/adb_keys and /data/property/persistent_properties with it --
# see the 2026-08-22 note above about why this is NOT a default.
ifeq ($(NX809J_TEST_ADB),true)
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/testadb/adb_keys:$(TARGET_COPY_OUT_PRODUCT)/etc/security/adb_keys
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/testadb/00-testadb.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/00-testadb.rc
endif

# Personal (developer's own phone) extras on top of Minimal, 2026-09-03:
#     NX809J_PERSONAL=true m ...
# Google app (Velvet, 368 MB, priv-app in product from vendor/gms) + the Circle to Search
# framework strings (overlay-personal/). Minimal stays minimal for everyone else; Full already
# has both via gms_full.mk + PixelConfigOverlayCommon. Gated separately from NX809J_TEST_ADB
# because that flag is about the adb key, and a personal build must still be flashable
# through flash_super_dev.sh when it has no key baked. Requires GMS (Velvet is useless on
# microG), so it is a no-op there.
ifeq ($(NX809J_PERSONAL),true)
ifneq ($(NX809J_MICROG),true)
PRODUCT_PACKAGES += \
    Velvet
PRODUCT_PACKAGE_OVERLAYS += $(LOCAL_PATH)/overlay-personal
endif
endif
