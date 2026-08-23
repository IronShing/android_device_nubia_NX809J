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
#include <string>
#include <cstdlib>

using android::base::GetBoolProperty;
using android::base::SetProperty;
using android::base::ReadFileToString;

namespace {

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
    for (;;) {
        if (GetBoolProperty("persist.sys.rm.fan_auto", false)) {
            int c = readMaxTempC();
            int lvl;                       // hysteresis-friendly thresholds
            if      (c >= 70) lvl = 5;
            else if (c >= 62) lvl = 4;
            else if (c >= 54) lvl = 3;
            else if (c >= 46) lvl = 2;
            else if (c >= 40) lvl = 1;
            else              lvl = 0;
            if (lvl != lastLvl) {
                SetProperty("persist.sys.fan.level", std::to_string(lvl));
                lastLvl = lvl;
            }
        } else {
            lastLvl = -1;   // auto off: forget, so re-enabling re-applies
        }
        usleep(2 * 1000 * 1000);   // 2s poll
    }
    return 0;
}
