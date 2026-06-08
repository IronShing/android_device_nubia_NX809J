#
# Copyright (C) 2025 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
# iter 217: added full_base_telephony.mk — pulls in the ENTIRE Android framework
# (framework.jar, services.jar, SystemUI, APEXes, core apps, telephony stack).
# Without it, system.img was only 27 MB with no framework content = bootloop.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit from NX809J device
$(call inherit-product, device/nubia/NX809J/device.mk)

# Inherit common LineageOS configuration
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

# Device identifiers
PRODUCT_NAME := lineage_NX809J
PRODUCT_DEVICE := NX809J
PRODUCT_BRAND := nubia
PRODUCT_MODEL := NX809J
PRODUCT_MANUFACTURER := nubia
PRODUCT_SYSTEM_NAME := NX809J

PRODUCT_GMS_CLIENTID_BASE := android-zte

# PRODUCT_BUILD_PROP_OVERRIDES removed — TARGET_DEVICE/TARGET_PRODUCT are not valid override keys

BUILD_FINGERPRINT := nubia/NX809J/NX809J:16/AP3A/V11.0.14:user/release-keys
