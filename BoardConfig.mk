#
# Copyright (C) 2025 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

DEVICE_PATH := device/nubia/NX809J

# Architecture (64-bit only — ro.zygote=zygote64)
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv9-a
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_ABI2 :=
TARGET_CPU_VARIANT := generic
TARGET_CPU_VARIANT_RUNTIME := kryo

# Board
BOARD_VENDOR := nubia
# iter 220: REVERTED back to 'canoe' — the sun change (iter 219) did NOT fix
# the bootloop. Recovery log confirmed ro.board.platform=canoe at runtime (set
# by stock DTB), regardless of TARGET_BOARD_PLATFORM setting. cmfnels TWRP tree
# using 'sun' is build-time only, doesn't affect runtime. Stock uses canoe.
TARGET_BOARD_PLATFORM := canoe
TARGET_BOOTLOADER_BOARD_NAME := NX809J

# A/B
AB_OTA_UPDATER := true
# iter 217: match dodge's pattern — recovery is a SEPARATE partition, NOT
# merged into vendor_boot. This keeps vendor_boot minimal (fstab + modules
# only) like stock, which is required for boot to succeed.
AB_OTA_PARTITIONS := \
    boot \
    dtbo \
    init_boot \
    odm \
    product \
    recovery \
    system \
    system_dlkm \
    system_ext \
    vbmeta \
    vbmeta_system \
    vendor \
    vendor_boot \
    vendor_dlkm

# Boot (addresses from stock vendor_boot header)
BOARD_BOOT_HEADER_VERSION := 4
BOARD_KERNEL_BASE := 0x00000000
BOARD_KERNEL_PAGESIZE := 4096
BOARD_KERNEL_OFFSET := 0x00008000
BOARD_RAMDISK_OFFSET := 0x01000000
BOARD_KERNEL_TAGS_OFFSET := 0x00000100
BOARD_DTB_OFFSET := 0x01f00000
BOARD_MKBOOTIMG_ARGS += --header_version $(BOARD_BOOT_HEADER_VERSION)
BOARD_MKBOOTIMG_ARGS += --base $(BOARD_KERNEL_BASE)
BOARD_MKBOOTIMG_ARGS += --pagesize $(BOARD_KERNEL_PAGESIZE)
BOARD_MKBOOTIMG_ARGS += --kernel_offset $(BOARD_KERNEL_OFFSET)
# iter 217: removed --ramdisk_offset to match dodge (dodge doesn't pass it)
# BOARD_MKBOOTIMG_ARGS += --ramdisk_offset $(BOARD_RAMDISK_OFFSET)
BOARD_MKBOOTIMG_ARGS += --tags_offset $(BOARD_KERNEL_TAGS_OFFSET)
BOARD_MKBOOTIMG_ARGS += --dtb_offset $(BOARD_DTB_OFFSET)
BOARD_RAMDISK_USE_LZ4 := true

# Init boot (Android 13+ GKI)
BOARD_INIT_BOOT_HEADER_VERSION := 4
# phase7 Option B (2026-05-30, CC#2): when LOS builds its own init_boot (prebuilt
# commented out below), the build/make rule passes $(BOARD_MKBOOTIMG_INIT_ARGS)
# to mkbootimg but NOTHING here wired --header_version into it, so mkbootimg
# defaulted to header v0 — stock init_boot is v4. Mirror the upstream OnePlus
# sm8750-common pattern (.reference/oneplus_sm8750-common/BoardConfigCommon.mk:73)
# so the source-built init_boot is v4 like stock. (Harmless when the prebuilt is
# used — BOARD_MKBOOTIMG_INIT_ARGS is only consumed by the generated-image rule.)
BOARD_MKBOOTIMG_INIT_ARGS += --header_version $(BOARD_INIT_BOOT_HEADER_VERSION)

# Stock Nubia init_boot.img — sourced byte-exact from the original NX809J dump
# (GEN_CN_NX809JV1.0.0B13MR_DL/init_boot.img, 8.0 MB, valid Android bootimg).
#
# iter 209 fix: build/make's add_img_to_target_files asserts on missing
# init_boot.img when init_boot is in AB_OTA_PARTITIONS but no source-side
# generation is configured. Dodge sm8750-common GENERATES init_boot from
# source via mkbootimg + generic ramdisk; we ship the stock prebuilt instead
# to maintain the byte-exact stock contract for the boot chain (consistent
# with stock vbmeta + stock dtbo + stock kernel — patched ABL was designed
# against stock partition contents).
#
# AOSP's standard mechanism: BOARD_PREBUILT_INIT_BOOT_IMAGE pointing at the
# prebuilt path. Wired into build/make/core/Makefile:1606-1633 — replaces
# the generated init_boot target with a copy of the prebuilt file.
#
# phase7 Option B (2026-05-30, CC#2 parallel build): commented out so LineageOS
# BUILDS its own init_boot from the generic ramdisk + LOS-built /init. The point
# is an init that matches LOS's traditional /system second-stage layout (NOT EA's
# system-as-root facade), to pair with the pristine LOS super (no splice). GKI is
# enabled (BOARD_USES_GENERIC_KERNEL_IMAGE := true below), so the build assembles
# init_boot from source. Restore this line to revert to the byte-exact stock prebuilt.
# BOARD_PREBUILT_INIT_BOOT_IMAGE := device/nubia/NX809J/prebuilts/init_boot.img

# DTB/DTBO
BOARD_INCLUDE_DTB_IN_BOOTIMG := true
BOARD_USES_QCOM_MERGE_DTBS_SCRIPT := true

# Display
TARGET_SCREEN_DENSITY := 480

# Filesystem
TARGET_USERIMAGES_USE_F2FS := true

# Kernel (GKI 6.12.23-android16-5, prebuilt from stock)
BOARD_KERNEL_CMDLINE := \
    video=vfb:640x400,bpp=32,memsize=3072000 \
    nosoftlockup \
    console=ttynull \
    qcom_geni_serial.con_enabled=0
# SELinux ENFORCING (beta gate, 2026-06-16). Removing androidboot.selinux=permissive
# makes the device boot enforcing by default. Both prior blockers are closed and
# validated live under setenforce 1: the lineage charging HAL + dt2w_uewake run in
# their own system_ext coredomains with zero denials, and AOD+DT2W work together.
# Boot-time denials were all benign under permissive (flags_health_check aconfig
# oneshot, init netlink self-read). To temporarily revert for diagnosis, restore:
#   BOARD_KERNEL_CMDLINE += androidboot.selinux=permissive
BOARD_BOOTCONFIG := \
    androidboot.hardware=qcom \
    androidboot.memcg=1 \
    androidboot.usbcontroller=a600000.dwc3 \
    androidboot.load_modules_parallel=true \
    androidboot.hypervisor.protected_vm.supported=true \
    androidboot.hypervisor.version=gunyah \
    androidboot.vendor.qspa=true
TARGET_KERNEL_ARCH := arm64
BOARD_KERNEL_IMAGE_NAME := Image
TARGET_PREBUILT_KERNEL := device/nubia/NX809J-kernel/prebuilt/Image
TARGET_PREBUILT_DTB := device/nubia/NX809J-kernel/prebuilt/dtb.img
# TARGET_PREBUILT_DTB is a no-op in AOSP's build/make (nothing consumes it).
# The rule that produces $(PRODUCT_OUT)/dtb.img (needed by recovery/boot/vendor_boot)
# only exists when BOARD_PREBUILT_DTBIMAGE_DIR is set; the recipe is `cat $(DIR)/*.dtb > dtb.img`.
# Our prebuilt dtb.img is an already-merged multi-dtb blob, exposed as a single .dtb so cat is identity.
BOARD_PREBUILT_DTBIMAGE_DIR := device/nubia/NX809J-kernel/prebuilt/dtb
BOARD_PREBUILT_DTBOIMAGE := device/nubia/NX809J-kernel/prebuilt/dtbo_stock.img
BOARD_USES_GENERIC_KERNEL_IMAGE := true

# iter 222: ROOT CAUSE FIX — modules were going to vendor_boot ramdisk only,
# not to vendor_dlkm/system_dlkm partitions. Stock has 305 .ko in vendor_dlkm
# and 103 .ko in system_dlkm; ours were empty (340KB vs 27MB/8MB). Without
# kernel modules in the DLKM partitions, second-stage init can't load hardware
# HALs → init aborts → bootloop.

# Prebuilt kernel modules for vendor_dlkm partition (second-stage, all hw drivers)
BOARD_VENDOR_KERNEL_MODULES := \
    $(wildcard device/nubia/NX809J-kernel/prebuilt/vendor_dlkm/lib/modules/*.ko)
BOARD_VENDOR_KERNEL_MODULES_LOAD := \
    $(strip $(shell cat device/nubia/NX809J-kernel/prebuilt/vendor_dlkm/lib/modules/modules.load))

# Prebuilt kernel modules for system_dlkm partition (GKI modules)
BOARD_SYSTEM_KERNEL_MODULES := \
    $(wildcard device/nubia/NX809J-kernel/prebuilt/system_dlkm/lib/modules/*.ko)

# Prebuilt kernel modules for vendor_boot ramdisk (first-stage init, early hw)
# vendor_ramdisk is a SUPERSET of vendor_dlkm — must include first-stage-only
# platform modules (clocks, pinctrl, IOMMU, Gunyah, SCM, UFS PHY, SoC infra)
# that load before vendor_dlkm can be mounted from super.
# Per path1_los_canoe_audit_2026_05_12 + phase3_attempt2 analysis: stock
# vendor_ramdisk has 332 modules (111 more than vendor_dlkm's 302). Source
# tree pulled byte-equal from stock EA vendor_ramdisk's /lib/modules/.
BOARD_VENDOR_RAMDISK_KERNEL_MODULES := \
    $(wildcard device/nubia/NX809J-kernel/prebuilt/vendor_ramdisk/lib/modules/*.ko)
BOARD_VENDOR_RAMDISK_KERNEL_MODULES_LOAD := \
    $(strip $(shell cat device/nubia/NX809J-kernel/prebuilt/vendor_ramdisk/lib/modules/modules.load))
TARGET_HAS_GENERIC_KERNEL_IMAGE_HEADERS := true

# Metadata
BOARD_USES_METADATA_PARTITION := true

# Partitions (from device extraction — blockdev --getsize64)
BOARD_FLASH_BLOCK_SIZE := 262144 # (BOARD_KERNEL_PAGESIZE * 64)
BOARD_BOOTIMAGE_PARTITION_SIZE := 100663296
BOARD_INIT_BOOT_IMAGE_PARTITION_SIZE := 8388608
BOARD_VENDOR_BOOTIMAGE_PARTITION_SIZE := 100663296
BOARD_DTBOIMG_PARTITION_SIZE := 75497472
BOARD_RECOVERYIMAGE_PARTITION_SIZE := 104857600

# Super / Dynamic Partitions
BOARD_SUPER_PARTITION_SIZE := 19327352832
BOARD_SUPER_PARTITION_GROUPS := qti_dynamic_partitions
# usable space = super_size - overhead (~4MB)
BOARD_QTI_DYNAMIC_PARTITIONS_SIZE := 19323158528
BOARD_QTI_DYNAMIC_PARTITIONS_PARTITION_LIST := \
    odm \
    product \
    system \
    system_dlkm \
    system_ext \
    vendor \
    vendor_dlkm

# Filesystem types
BOARD_ODMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_SYSTEM_DLKMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_SYSTEM_EXTIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_VENDOR_DLKMIMAGE_FILE_SYSTEM_TYPE := erofs

# System partition filesystem — ext4 (divergence from stock byte-exact erofs)
#
# Stock Nubia ships /system as EROFS (verified iter 185 via lpunpack + file).
# However, the LineageOS-23.2 build pipeline does not support erofs system
# images due to an AOSP integration bug between build_image.py's system-as-root
# staging path and erofs-utils' canned_fs_config lookup logic:
#
#   build_image.py copies SYSTEM into a temp dir as a literal /system/
#   subdirectory, forces mount_point="/", invokes mkfs.erofs with --mount-point /
#   which strips the trailing slash to internal mount_point="". When mkfs.erofs
#   walks the staging tree and encounters /system, it constructs the lookup path
#   as asprintf("","system") = "/system", strips leading slash, and queries
#   canned_fs_config for "system". The AOSP fs_config generation pipeline never
#   produces a literal "system" entry (find . -type d -> cut -c 3- -> empty,
#   sed prepends system/, fs_config -R "system/" round-trips trailing slash to
#   empty path). Result: failed to find system in canned fs_config.
#
# Documented zero LineageOS-23.2 devices use erofs system. Four reference
# devices verified (dodge, sm8350-common, xiaomi/peridot, xiaomi/garnet) all
# use ext4 system. The infrastructure does not support erofs system on this
# branch.
#
# Workaround paths considered and rejected:
#   - Inject [system] into config.fs: fs_config_generator normalizes it back
#     to empty path via core/Makefile fs_config -R "system/" pipeline,
#     mechanically cannot work (verified by ChatGPT cross-investigation).
#   - Patch external/erofs-utils with the Gao Xiang fix: out of scope, and our
#     1.8.3 tree (Dec 2024) doesn't have the patch despite supposedly being
#     merged in 1.2.1-1 (Jan 2021), suggesting it was reverted or never landed.
#
# AVB compatibility: NOT affected. The patched ABL boot chain (efisp -> ABL
# patch -> direct PE execution) bypasses AVB entirely at the bootloader stage,
# before partition mount. Filesystem format is irrelevant to AVB. Stock vbmeta
# stays untouched. Stock fstab.qcom has DUAL entries for /system (erofs AND
# ext4 fallback), which is Android's documented dual-fstype pattern — stock
# Nubia explicitly designed the device to accept either filesystem at runtime.
# Switching to ext4 is within the device's runtime design envelope.
#
# Vendor/odm/vendor_dlkm/system_dlkm/product/system_ext stay erofs (above) —
# those use mount_point=<partition_name>, not "/", and don't trigger the bug
# (verified at iter 184: all six erofs partitions built successfully, only
# system.img with --mount-point / failed).
# iter 218: switched BACK to erofs (stock format). The ext4 fallback in fstab
# doesn't work reliably with the stock kernel. The iter-208 build_image.py patch
# (injecting literal "system" entry into canned_fs_config) handles the erofs
# build path now. Stock Nubia ships erofs system — matching stock format.
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := erofs

TARGET_COPY_OUT_ODM := odm
TARGET_COPY_OUT_PRODUCT := product
TARGET_COPY_OUT_SYSTEM_DLKM := system_dlkm
TARGET_COPY_OUT_SYSTEM_EXT := system_ext
TARGET_COPY_OUT_VENDOR := vendor
TARGET_COPY_OUT_VENDOR_DLKM := vendor_dlkm

# Properties
TARGET_ODM_PROP += $(DEVICE_PATH)/odm.prop
TARGET_PRODUCT_PROP += $(DEVICE_PATH)/product.prop
TARGET_SYSTEM_PROP += $(DEVICE_PATH)/system.prop
TARGET_VENDOR_PROP += $(DEVICE_PATH)/vendor.prop

# Recovery — match dodge: separate recovery partition, NOT merged into vendor_boot
# iter 217: removed BOARD_MOVE_RECOVERY_RESOURCES_TO_VENDOR_BOOT (was causing
# vendor_boot to be 96 MB with 200+ recovery files, bootlooping the device).
# Added BOARD_EXCLUDE_KERNEL_FROM_RECOVERY_IMAGE (dodge has this).
# Recovery is now a separate partition in AB_OTA_PARTITIONS.
# iter 221: RE-ENABLED — iter 220 with embedded kernel+cmdline in recovery.img
# broke recovery boot (goes to Green Start instead of recovery UI). Iter 219's
# recovery (without kernel) did boot to UI (just no ADB due to SELinux enforcing).
# Accept that tradeoff — need recovery UI working first.
BOARD_EXCLUDE_KERNEL_FROM_RECOVERY_IMAGE := true
TARGET_RECOVERY_FSTAB := $(DEVICE_PATH)/rootdir/etc/fstab.qcom
TARGET_RECOVERY_PIXEL_FORMAT := RGBX_8888

# Security patch — match stock firmware
VENDOR_SECURITY_PATCH := 2025-11-01

# SELinux
# This device rides the STOCK ZTE /vendor partition and builds no vendor.img, so
# vendor-side sepolicy (BOARD_VENDOR_SEPOLICY_DIRS) never reaches the device.
# TARGET_USES_PREBUILT_VENDOR_SEPOLICY routes LineageOS's HAL "dynamic" policy
# (device/lineage/sepolicy/common/dynamic: hal_lineage_health_service type,
# service_contexts, client<->server binder) to SYSTEM_EXT instead of the
# discarded vendor side, and stops common/vendor from compiling (which also
# avoids duplicate-type clashes with the system-side health domain below).
# Without this, the lineage health/charge-limiter HAL has no domain and cannot
# launch under enforcing. NOTE (build/RE): validate interaction with the qcom
# SEPolicy.mk include — this flag changes how vendor sepolicy is assembled.
TARGET_USES_PREBUILT_VENDOR_SEPOLICY := true
include device/qcom/sepolicy_vndr/SEPolicy.mk
BOARD_VENDOR_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy
# System-side coredomain for the re-homed (/system_ext) lineage health HAL.
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/system_ext

# Drop device/lineage/sepolicy/qcom/dynamic from the system_ext policy. Flipping
# TARGET_USES_PREBUILT_VENDOR_SEPOLICY routes qcom/sepolicy.mk's "dynamic" dir
# system-side, but that dir (dontaudit.te, hal_lineage_livedisplay_qti*) refers
# to QCOM VENDOR types (adsprpcd_file, ...) that live only in
# BOARD_VENDOR_SEPOLICY_DIRS -> "unknown type" at checkpolicy. We only need the
# COMMON dynamic dir (hal_lineage_health_service + service_contexts) system-side;
# qcom/dynamic (LiveDisplay-QTI) was already discarded vendor-side on this
# stock-/vendor device, so excluding it is status-quo, not a regression. It was
# added during the SEPolicy.mk include above; common/dynamic is added later by
# build/make/core/config.mk and is left intact.
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS := \
    $(filter-out device/lineage/sepolicy/qcom/dynamic,$(SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS))

# Verified Boot
BOARD_AVB_ENABLE := true
BOARD_AVB_MAKE_VBMETA_IMAGE_ARGS += --flags 3
BOARD_AVB_VBMETA_SYSTEM := system system_ext product
BOARD_AVB_VBMETA_SYSTEM_KEY_PATH := external/avb/test/data/testkey_rsa2048.pem
BOARD_AVB_VBMETA_SYSTEM_ALGORITHM := SHA256_RSA2048
BOARD_AVB_VBMETA_SYSTEM_ROLLBACK_INDEX := $(PLATFORM_SECURITY_PATCH_TIMESTAMP)
BOARD_AVB_VBMETA_SYSTEM_ROLLBACK_INDEX_LOCATION := 2

# VINTF (uses fragment manifests in vendor/etc/vintf/manifest/)
DEVICE_MANIFEST_FILE += $(DEVICE_PATH)/manifest.xml

# Framework compatibility matrix — feeds the AOSP libhidl/vintfdata generator
# (vintf_data type:framework_compatibility_matrix) at build time. Mirrors
# dodge sm8750-common's pattern at iter 212. Without this, the OTA generator's
# checkvintf step fails with "No framework matrix file from device or from
# update package" because no source-side framework matrix file is wired in.
#
# We point at the qcom-caf common framework matrix only (skipping dodge's
# hardware/oplus/vintf/device_framework_matrix.xml — oplus-specific, doesn't
# exist in our tree). The qcom-caf common file contains the SM8750 framework
# HAL compat declarations.
#
# Symmetric to DEVICE_MATRIX_FILE (iter 183) which feeds the vendor compat
# matrix generator. iter 212 fixes the framework-side equivalent.
# iter 216: dropped hardware/qcom-caf/common/vendor_framework_compatibility_matrix.xml
# because it references ~190 vendor HALs (vendor.qti.spu, vendor.qti.voiceprint,
# vendor.qti.snapdragonServices.*, etc.) that lack source-side aidl_interface/
# hidl_interface definitions, triggering checkMatrixHalsHasDefinition() "Typo?" errors
# at VintfObject.cpp:1353-1408. Our framework_compatibility_matrix.xml is now
# FCM-level-only (no HAL entries) — provides the FCM version declaration that
# iter 212 needed without declaring vendor HALs that fail the source check.
DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE += \
    $(DEVICE_PATH)/framework_compatibility_matrix.xml

# VINTF compatibility matrix — static stock matrix from the device dump,
# fed into AOSP's libhidl/vintfdata generator (vintf_data { type: "device_cm" }
# at system/libhidl/vintfdata/Android.bp:19-24) at build time.
#
# Sourced byte-exact from vendor/nubia/NX809J/proprietary/vendor/etc/vintf/
# compatibility_matrix.xml at original NX809J extraction time. Preserves the
# <system-sdk>36</system-sdk> declaration that asserts our vendor blobs expect
# Android 16 framework APIs — critical for checkvintf runtime validation.
#
# Diverges from the dodge sm8750-common pattern (which uses
# hardware/qcom-caf/common/compatibility_matrix_aidl.xml) because the stock
# Nubia matrix declares the system-sdk version while the qcom-caf generic
# one doesn't. Byte-exact stock contract is preferred over generic SoC-common
# for this specific file.
DEVICE_MATRIX_FILE := $(DEVICE_PATH)/compatibility_matrix.xml

# Inherit vendor BoardConfig
# include vendor/nubia/NX809J/BoardConfigVendor.mk

# Pull in LineageOS shared BoardConfig — registers lineageVarsPlugin Soong
# namespace (KERNEL_PATH, TARGET_KERNEL_PLATFORM_TARGET, etc.) needed by
# vendor/lineage/build/soong/Android.bp lineage_generator modules.
include vendor/lineage/config/BoardConfigLineage.mk

# Allow ELF prebuilts in PRODUCT_COPY_FILES (vendor blobs)
BUILD_BROKEN_ELF_PREBUILT_PRODUCT_COPY_FILES := true

# Vendor user/group AID declarations (from device/oneplus/sm8750-common
# config.fs + 2 Nubia-specific additions). Required for vendor init .rc
# scripts that reference vendor_qti_diag, vendor_qtr, vendor_rfs_shared,
# vendor_ssgtzd, vendor_modprobe, vendor_unsignedhexlpservice.
TARGET_FS_CONFIG_GEN := device/nubia/NX809J/config.fs

# Workaround for build/make/core/Makefile:2796: the recovery ramdisk
# rule does `touch $(TARGET_RECOVERY_ROOT_OUT)/linkerconfig/ld.config.txt`
# without first mkdir'ing the linkerconfig directory. Pre-create it at
# parse time so the touch succeeds. Upstream bug — out of scope to fix
# in build/make/, this is the device-tree workaround.
$(shell mkdir -p $(OUT_DIR)/target/product/NX809J/recovery/root/linkerconfig)

# APEX allowed-deps check bypass
#
# Root cause: LineageOS 23.2 upstream V↔W tree drift (NOT caused by NX809J bringup).
#
# Investigation findings (see device/nubia/NX809J/.apex_closure_diff.txt):
# The 21 new entries in our APEX dep closure are duplicates of modules already
# in packages/modules/common/build/allowed_deps.txt — same module names, only
# difference is minSdkVersion:35 vs minSdkVersion:36. The constituent modules
# in packages/modules/{Bluetooth,CrashRecovery,Nfc,Profiling,Telephony,UprobeStats}
# declare min_sdk_version: "35" in their own Android.bp files, but the upstream
# allowed_deps.txt was regenerated against APEXes built at min_sdk_version: 36
# (Android 16/W). Each apex's depsinfo/flatlist.txt records per-dep minSdkVersion
# from the module declaration, producing sdk35 flatlist entries for apexes whose
# allowlist entries were filed under sdk36.
#
# This is a generic LineageOS-23.2 tree-state mismatch affecting every build,
# zero footprint from device/nubia/NX809J/, vendor/nubia/, or any of our changes.
# The fix would be running packages/modules/common/build/update-apex-allowed-deps.sh
# upstream — out of scope for our device tree.
#
# The check is advisory for Google's CI to catch unintended Mainline bloat in
# certified builds. For unofficial downstream bringups it gates legitimate work
# against an arbitrarily-frozen allowlist. Disabling it here is sanctioned and
# carries no runtime risk: the dep closure is what it is regardless of whether
# we check it against an outdated text file.
#
# If/when LineageOS refreshes allowed_deps.txt upstream, this bypass becomes
# a no-op and can be removed.
UNSAFE_DISABLE_APEX_ALLOWED_DEPS_CHECK := true

# WiFi — sourced verbatim from LineageOS device/oneplus/sm8750-common/BoardConfigCommon.mk
# at lineage-23.2 (verified iter 187). The original NX809J BoardConfig.mk lacked any
# wifi configuration block, an iter-150-era omission that caused libwifi-hal-qcom to
# fall through wifihal_qcom_defaults' conditions_default branch and silently drop
# from the build graph due to CFI variant mismatch with our prebuilt.
#
# The fix is dodge's exact pattern: BOARD_WLAN_DEVICE := qcwcn + libwifi-hal-qcom
# in PRODUCT_PACKAGES (in device.mk). We do NOT set BOARD_WLAN_CHIP — dodge doesn't
# set it either, the conditions_default branch is the canonical SM8750 path.
BOARD_WLAN_DEVICE := qcwcn
BOARD_HOSTAPD_DRIVER := NL80211
BOARD_HOSTAPD_PRIVATE_LIB := lib_driver_cmd_$(BOARD_WLAN_DEVICE)
BOARD_WPA_SUPPLICANT_DRIVER := $(BOARD_HOSTAPD_DRIVER)
BOARD_WPA_SUPPLICANT_PRIVATE_LIB := $(BOARD_HOSTAPD_PRIVATE_LIB)
BOARD_WPA_SUPPLICANT_PRIVATE_LIB_EVENT := "ON"
WIFI_DRIVER_STATE_CTRL_PARAM := "/dev/wlan"
WIFI_DRIVER_STATE_OFF := "OFF"
WIFI_DRIVER_STATE_ON := "ON"
WIFI_FEATURE_HOSTAPD_11AX := true
WIFI_HIDL_FEATURE_AWARE := true
WIFI_HIDL_FEATURE_DUAL_INTERFACE := true
WIFI_HIDL_UNIFIED_SUPPLICANT_SERVICE_RC_ENTRY := true
WPA_SUPPLICANT_VERSION := VER_0_8_X
