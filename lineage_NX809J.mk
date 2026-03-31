#
# Copyright (C) 2025 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# 64-bit only (must come before core_minimal.mk inheritance chain)
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)

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

PRODUCT_BUILD_PROP_OVERRIDES += \
    PRIVATE_BUILD_DESC="NX809J-user 16 AP3A release-keys" \
    TARGET_DEVICE=NX809J \
    TARGET_PRODUCT=NX809J

BUILD_FINGERPRINT := nubia/NX809J/NX809J:16/AP3A/V11.0.14:user/release-keys
