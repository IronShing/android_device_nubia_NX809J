# Launcher3: enable phone taskbar for desktop mode (app-drawer fix)

VALIDATED 2026-06-30 (super_v35): dp.isTaskbarPresent flipped false->true, boots
Enforcing, 0 denials.

## Bug
In desktop windowing mode the taskbar had NO app drawer -> empty desktop, apps
wouldn't launch (XDA cmcb1245). Root cause: Launcher3 DeviceProfile gates the phone
taskbar on enableTinyTaskbar(), which is FUSED OFF in this build, while its sibling
enableTaskbarOnPhones() is ENABLED (and SystemUI's NavigationBarControllerImpl already
uses it). Phone is sw374dp (< 600dp tablet threshold) so isTablet=false -> isTaskbarPresent=false.

## Fix (packages/apps/Launcher3, system_ext priv-app)
Make Launcher3 also honor the enabled enableTaskbarOnPhones():
- DeviceProfile.java: taskbarOrBubbleBarOnPhones |= enableTaskbarOnPhones()
- TaskbarActivityContext.java isTinyTaskbar(): (enableTinyTaskbar() || enableTaskbarOnPhones())
Re-apply 0001-*.patch if a repo sync resets Launcher3.

Side effect (intended): a slim phone taskbar now shows in normal use too — that's the
desktop-class behavior and the only way to get the app drawer in desktop mode.
