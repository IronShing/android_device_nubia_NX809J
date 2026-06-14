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

# IR remote: the HAL ships in the stock vendor (vendor.ir-default +
# consumerir.zte.so). We ship only the consumerir feature permission so a
# user-installed IR app works; the proprietary KooKong app is NOT bundled
# (redistribution). Sideload an IR remote app of your choice.
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
PRODUCT_PACKAGES += dt2w_uewake
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/dt2w/dt2w_uewake.kl:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/keylayout/dt2w_uewake.kl

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
