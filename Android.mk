#
# Copyright (C) 2025 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

LOCAL_PATH := $(call my-dir)
# Included sub-makefiles reassign LOCAL_PATH (prebuilt/AndroidAuto does), so keep our own copy
# for the explicit include below -- the first nested Android.mk broke it (2026-09-03).
NX809J_DEVICE_PATH := $(LOCAL_PATH)

ifeq ($(TARGET_DEVICE),NX809J)
include $(call all-makefiles-under,$(LOCAL_PATH))
include $(NX809J_DEVICE_PATH)/rootdir/etc/Android.mk
endif
