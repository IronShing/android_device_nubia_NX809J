# ViPER4Android RE — ROM bake (include from device.mk:  $(call inherit-product, device/nubia/NX809J/audio/viper4android/viper4android.mk))
PRODUCT_PACKAGES += \
    ViPER4Android \
    libv4a_aidl

# Effect declaration is MERGED into the vendor audio_effects_config at vendor-repack time
# (v4a_effect.xml has the <library>/<effect> lines). Since we ship stock vendor, the merge is a
# vendor-image edit, not a Soong copy — see BAKE_RECIPE.md.

# Enable V4A Global mode by default (session-0 attach; the per-device path fails on this HAL because
# the Speaker route has no address). Shipped as a product prop the app honors on first run.
PRODUCT_PRODUCT_PROPERTIES += \
    persist.viper4android.global_mode=1 \
    persist.viper4android.autostart=1
