// NX809J hwcontrol — RedMagic auto-fan daemon (system_ext coredomain).
//
// Coredomain-clean work only: reads the thermal zones and, when the user enables
// Auto fan (persist.sys.rm.fan_auto=1), sets persist.sys.fan.level to a
// temperature-mapped level. vendor_init (redmagic_hw_arm.rc) does the actual
// /sys/kernel/fan write off that property — a coredomain can't write the vendor-
// labelled hardware nodes. RGB / edge-reject / triggers / haptics are likewise
// applied by vendor_init off the persist.sys.rm.* props the app sets.

#include <android-base/properties.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <unistd.h>
#include <algorithm>
#include <string>
#include <cstdlib>

using android::base::GetBoolProperty;
using android::base::GetProperty;
using android::base::SetProperty;
using android::base::ReadFileToString;

namespace {

// ---- fan-ring RGB follows the auto fan ----
//
// Lights the fan ring only while the automatic fan is actually spinning, so the ring is a
// readout of "the phone is cooling itself" rather than static decoration.
//
// We do NOT write persist.sys.rm.led.fan: that is the user's own Lighting setting, and
// clobbering it would lose their colour the first time the fan spun (the same mistake
// ChargeCooling documents for persist.sys.fan.level). Instead we publish a request pair and
// let vendor_init drive the LED, exactly like chargecool.active -- on release it re-writes
// the user's value, so there is no saved state to drift.
constexpr char kFollowProp[]  = "persist.sys.rm.fan_rgb_follow";
constexpr char kActiveProp[]  = "persist.sys.rm.fan_rgb.active";
constexpr char kValueProp[]   = "persist.sys.rm.fan_rgb.value";
constexpr char kUserLedProp[] = "persist.sys.rm.led.fan";
// Encoding is 0x<pos><eee><ccc> -- the same string the settings app writes. Fan ring is
// position 3, effect 002 = constant, and colours 101-108 are the multi-colour RGB presets,
// which ship for the fan ring only (aw_fan*_10{1..8}.bin).
constexpr char kOnEffect[]      = "002";        // constant: a plain "it is on" effect
constexpr char kFallbackValue[] = "0x3002101";  // constant + RGB 1, used only if nothing is set

// What to light the ring with.
//
// Three cases, in order:
//   1. The zone is already set to something lit -> use it verbatim, effect and all.
//   2. The zone has a colour but its effect is Off (000) -> keep THEIR colour and substitute a
//      constant effect. Turning the zone off in the Lighting tab is a statement about the idle
//      ring, not a request to discard the colour, and the previous code threw the colour away
//      and fell back to red -- a user with RGB 3 selected got red, which is not what they asked
//      the ring to be.
//   3. Nothing usable stored -> an RGB preset, not red.
std::string fanRingValue() {
    const std::string v = GetProperty(kUserLedProp, "");
    const bool wellFormed = v.size() == 9 && v.rfind("0x", 0) == 0;
    if (!wellFormed) return kFallbackValue;
    if (v.compare(3, 3, "000") != 0) return v;                  // already lit
    return v.substr(0, 3) + kOnEffect + v.substr(6, 3);          // their colour, made visible
}

// Only sensors a fan can actually do something about: the SoC complexes and the battery.
//
// Verified on hardware 2026-08-20 at idle: cpu-*/gpuss-*/ddr sat at 28-29 C and batt2-therm at
// 34 C, while pmih010x_lite_tz -- a PMIC rail -- read 66 C. Chasing that rail would hold the fan
// at level 4 permanently on a cold phone, which is not a fan controller, just noise.
bool isFanRelevantZone(const std::string& type) {
    static const char* kPrefixes[] = { "cpu-", "cpuss", "cpullc", "gpuss", "nsp", "ddr", "batt" };
    for (const char* pfx : kPrefixes) {
        if (type.rfind(pfx, 0) == 0) return true;   // starts_with
    }
    return false;
}

// Hottest REAL sensor, in degrees C.
//
// Not every thermal_zone is a thermometer. On this device cpu-hw-trip-0 and cpu-hw-trip-1 are
// trip-point pseudo-zones that report a constant 105000 (105 C). Taking a naive max over all
// zones therefore always returned 105, which pins the fan at level 5 forever -- verified on
// hardware 2026-08-20, where the real sensors read 29-37 C at idle.
//
// So: skip any zone whose type contains "trip", and ignore implausible readings. A genuine skin
// or CPU sensor on this phone does not sit above 95 C; anything that does is a threshold
// constant, not a temperature.
int readMaxTempC() {
    int maxm = 0;
    for (int z = 0; z < 40; z++) {
        const std::string base = "/sys/class/thermal/thermal_zone" + std::to_string(z);

        std::string type;
        if (!ReadFileToString(base + "/type", &type)) continue;
        while (!type.empty() && (type.back() == '\n' || type.back() == '\r')) type.pop_back();

        if (type.find("trip") != std::string::npos) {
            continue;                       // trip-point pseudo-zone, not a sensor
        }
        if (!isFanRelevantZone(type)) {
            continue;                       // PMIC rails etc. -- not what a fan can influence
        }

        std::string t;
        if (!ReadFileToString(base + "/temp", &t)) continue;
        int mv = atoi(t.c_str());
        if (mv >= 95000) continue;          // threshold constant, not a real reading
        if (mv > maxm) maxm = mv;
    }
    return maxm / 1000;
}

}  // namespace

int main() {
    LOG(INFO) << "hwcontrol: started (auto-fan)";
    int lastLvl = -1;
    int lastRgb = -1;          // -1 = unknown, so the first pass always publishes
    for (;;) {
        // Charge cooling asks for the curve with chargecool.active=2 ("Fan speed while charging =
        // Auto"). Honour it even when the user has the fan set to a fixed speed or Off the rest
        // of the time -- otherwise picking Auto there would only ever run the pump.
        const bool chargeAuto = GetProperty("persist.sys.rm.chargecool.active", "0") == "2";
        // Game mode with "Fan speed while gaming = Auto". Read here rather than having gameperfd
        // publish a floor: gameperfd owns the pump and the perf lock, hwcontrol owns the curve.
        const bool gameAuto =
            GetProperty("persist.sys.power_mode_perf", "0") == "1" &&
            GetBoolProperty("persist.sys.rm.gamecool", true) &&
            GetProperty("persist.sys.rm.gamecool.fan", "auto") == "auto";
        if (chargeAuto || gameAuto || GetBoolProperty("persist.sys.rm.fan_auto", true)) {
            int c = readMaxTempC();
            int lvl;                       // hysteresis-friendly thresholds
            if      (c >= 70) lvl = 5;
            else if (c >= 62) lvl = 4;
            else if (c >= 54) lvl = 3;
            else if (c >= 46) lvl = 2;
            else if (c >= 40) lvl = 1;
            else              lvl = 0;

            // Floor while charging/gaming asks for Auto.
            //
            // Without this, "Auto" during a fast charge did nothing visible: a phone you have
            // just plugged in is ~30 C, the curve returns 0, and `on property:fan.level=0` in
            // redmagic_hw_arm.rc writes fan_enable 0 -- which cancels the fan_enable 1 that the
            // chargecool.active=2 trigger had just set. Pump ran, fan never spun, and the ring
            // stayed dark (it needs level > 0, and the aw22xxx LED needs the fan rail up at all).
            // Observed on hardware 2026-08-29. A floor makes "cool while charging" mean something
            // immediately while the curve still takes over as soon as it asks for more.
            int floorLvl = 0;
            if (chargeAuto) {
                floorLvl = android::base::GetIntProperty(
                    "persist.sys.rm.chargecool.floor", 2, 0, 5);
            }
            if (gameAuto) {
                floorLvl = std::max(floorLvl, android::base::GetIntProperty(
                    "persist.sys.rm.gamecool.floor", 2, 0, 5));
            }
            if (lvl < floorLvl) lvl = floorLvl;

            if (lvl != lastLvl) {
                SetProperty("persist.sys.fan.level", std::to_string(lvl));
                lastLvl = lvl;
            }

            // Ring on exactly while the fan is spinning under auto control.
            const int want = (lvl > 0 && GetBoolProperty(kFollowProp, true)) ? 1 : 0;
            if (want != lastRgb) {
                if (want) SetProperty(kValueProp, fanRingValue());
                SetProperty(kActiveProp, want ? "1" : "0");
                lastRgb = want;
            }
        } else {
            lastLvl = -1;   // auto off: forget, so re-enabling re-applies
            // Hand the ring back to the user's Lighting setting when auto mode is turned off
            // mid-spin, otherwise it would stay stuck on our value.
            if (lastRgb != 0) {
                SetProperty(kActiveProp, "0");
                lastRgb = 0;
            }
        }
        usleep(2 * 1000 * 1000);   // 2s poll
    }
    return 0;
}
