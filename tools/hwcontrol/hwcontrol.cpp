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

int readMaxTempC() {
    int maxm = 0;
    for (int z = 0; z < 40; z++) {
        std::string t;
        std::string p = "/sys/class/thermal/thermal_zone" + std::to_string(z) + "/temp";
        if (!ReadFileToString(p, &t)) continue;
        int mv = atoi(t.c_str());
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
