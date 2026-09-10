# Android Auto plumbing. Inherited when NX809J_SHIP_AA=true (evolution_NX809J.mk, policy
# 2026-09-04): FullGApps ships it; NX809J_PERSONAL=true (dev's Minimal) ships it;
# published Minimal and microG builds ship no Android Auto at all.
#
# Why this exists (found 2026-09-02): on those builds the SYSTEM_AUTOMOTIVE_PROJECTION role had
# no holder and no gearhead package was preinstalled, so an Android Auto installed from Play /
# APKMirror landed as a plain user app -- the role is systemOnly and the signature|privileged
# permissions it needs (MANAGE_USB, MODIFY_AUDIO_ROUTING, COMPANION_*, ...) can never be granted
# to it. Full works only because gms_full.mk ships AndroidAutoStubPrebuilt and the Pixel RRO
# GoogleConfigOverlay (WITH_GMS=true only) names gearhead as the projection role holder.
#
# So do the same thing Full does, without the rest of the Pixel tier:
#   - preinstall Google's signed Android Auto as a product priv-app. Originally the vendor/gms
#     stub (v1.2.565520-stub, verified 2026-09-02: Aurora updated it to 17.4 in place, priv
#     status + permissions kept); now the real 17.4.663054 split cluster in prebuilt/AndroidAuto
#     because Play region-blocks AA for some accounts and Aurora throttles -- works on first
#     boot instead of "go find the update". Play/Aurora still update it in place.
#   - device overlay config.xml names gearhead as the projection role default holder and
#     default notification-listener package;
#   - the gearhead privapp allowlist block. Minimal already gets it from
#     privapp-permissions-google-p.xml (product_blobs.mk); microG ships nothing from vendor/gms,
#     so it gets a copy of just that block.
#
# NOT the sn-00-x "AA as user app" patch set: its core hook sits in
# PermissionManagerServiceImpl.checkPermissionInternal, which no longer exists on A17 (permission
# checks moved into the Permission mainline module's Kotlin PermissionService). Preinstalling the
# stub needs no framework change at all.
#
# Whether AA is fully usable against microG (account/phenotype calls) is NOT yet verified on
# this device.
#
# Sideloaded car apps (e.g. CarStream -- YouTube on the head unit): AA only lists
# third-party car apps whose install source is Play unless its hidden developer "Unknown
# sources" toggle is on. frameworks/base ComputerEngine.getInstallSourceInfo reports Play as
# the installer when the CALLER is gearhead, gated by persist.sys.aa_fake_installsource
# (default on; `setprop persist.sys.aa_fake_installsource 0` to turn it off). Nothing else
# sees the spoof.

# Guarded like desktop/: the four APKs are Google's release binaries and are .gitignore'd, so a
# fresh clone does NOT contain them. Drop them into prebuilt/AndroidAuto/ (checksums and where
# to get them in prebuilt/AndroidAuto/README.md) and they are picked up automatically.
ifneq ($(wildcard device/nubia/NX809J/prebuilt/AndroidAuto/base.apk),)
PRODUCT_PACKAGES += \
    AndroidAutoPrebuilt
else
$(warning NX809J: prebuilt/AndroidAuto/base.apk absent - building without Android Auto. See prebuilt/AndroidAuto/README.md)
endif

ifeq ($(NX809J_MICROG),true)
PRODUCT_COPY_FILES += \
    device/nubia/NX809J/permissions/privapp-permissions-gearhead.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-gearhead.xml
endif
