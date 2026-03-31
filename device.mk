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

# API (Android 16, SDK 36)
PRODUCT_SHIPPING_API_LEVEL := 36

# Boot animation (1216x2688 from display extraction)
TARGET_SCREEN_HEIGHT := 2688
TARGET_SCREEN_WIDTH := 1216

# Boot control
PRODUCT_PACKAGES += \
    android.hardware.boot@1.2-impl-qti \
    android.hardware.boot@1.2-impl-qti.recovery \
    android.hardware.boot@1.2-service

# Display
PRODUCT_PACKAGES += \
    android.hardware.graphics.common-V6-ndk

# Dynamic partitions
PRODUCT_USE_DYNAMIC_PARTITIONS := true

# Fastbootd
PRODUCT_PACKAGES += \
    fastbootd

# Filesystem
PRODUCT_PACKAGES += \
    fs_config_files

# Firmware
$(call inherit-product-if-exists, vendor/nubia/NX809J/NX809J-vendor.mk)

# Gatekeeper
PRODUCT_PACKAGES += \
    android.hardware.gatekeeper@1.0-impl-qti \
    android.hardware.gatekeeper@1.0-service-qti

# Health
PRODUCT_PACKAGES += \
    android.hardware.health-service.qti \
    android.hardware.health-service.qti_recovery

# Init
PRODUCT_PACKAGES += \
    fstab.qcom \
    init.NX809J.rc

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/rootdir/etc/fstab.qcom:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/fstab.qcom

# IR Blaster (confirmed: consumerir.zte.so in vendor/lib64/hw/)
PRODUCT_PACKAGES += \
    android.hardware.ir-service

# Keymaster / Keymint
PRODUCT_PACKAGES += \
    android.hardware.security.keymint-V3-ndk

# NFC (ST54J — confirmed: nfc-service-aidl, init.nfc.st54j.rc)
PRODUCT_PACKAGES += \
    android.hardware.nfc-V1-ndk

# Overlays
PRODUCT_PACKAGES += \
    FrameworksResNX809J

# Partitions
PRODUCT_BUILD_SUPER_PARTITION := true

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)

# Update engine
PRODUCT_PACKAGES += \
    update_engine \
    update_engine_sideload \
    update_verifier

# USB
PRODUCT_PACKAGES += \
    android.hardware.usb-service.qti \
    android.hardware.usb.gadget-service.qti

# Vibrator (confirmed: vendor.qti.hardware.vibrator.service + zte_vibrator sysfs)
PRODUCT_PACKAGES += \
    android.hardware.vibrator-V2-ndk

# WiFi
PRODUCT_PACKAGES += \
    android.hardware.wifi-V2-ndk \
    android.hardware.wifi.hostapd-V2-ndk \
    android.hardware.wifi.supplicant-V3-ndk
