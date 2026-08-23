/*
 * gameperfd — NX809J game performance boost daemon.
 *
 * The platform GameManagerService/GameSpace stack already detects foreground games
 * (Settings.System "gamespace_game_list") and flips persist.sys.power_mode_perf=1 for
 * games the user set to "performance" mode. On stock RedMagic firmware a product-layer
 * component consumed that signal; the de-Googled build stripped it, so nothing boosts the
 * SoC. This daemon restores the last mile: when power_mode_perf==1 it holds a QTI perf
 * lock (CPU/GPU minimum-frequency floor) via libqti-perfd-client, which the QTI perf HAL
 * owns and applies (plain sysfs writes get reverted by the perf/thermal stack). Releases
 * the lock when the signal clears.
 *
 * Usage: gameperfd            -> daemon mode (watches the property)
 *        gameperfd test       -> acquire the boost for 28s and exit (opcode validation)
 */

#include <dirent.h>
#include <dlfcn.h>
#include <unistd.h>

#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

#include <android-base/logging.h>
#include <android-base/properties.h>

namespace {

// QTI perf-lock resource opcodes (validated live on NX809J against scaling_min_freq).
//   0x40800000 = policy0/scaling_min_freq (gold cluster, cores 0-5). Value in MHz; the perf HAL
//                rounds to the nearest OPP. VALIDATED: 2400 -> policy0 min 787->2496 MHz, reverts
//                cleanly on release (plain sysfs writes to this node get reverted; perf-lock sticks).
//   0x63C5C000 = msm_performance multi-core cpu_min_freq. Format: opcode, count, then (cpu,freqKHz)
//                pairs for cpus 1..8 (= cores 0..7). Mirrors the vendor's own launch boost and
//                floors the prime pair (cores 6-7) that policy-level scaling_min_freq does not expose.
// Floor chosen to remove governor ramp-lag during load spikes without pinning to max; the device
// has active fan + liquid cooling, and this engages only while a game is in the foreground.
//   0x42804000 = GPU min power level (kgsl). Value = the power-level index the GPU floor is pinned
//                to; LOWER = higher freq (the vendor's own game boost uses 5 @120fps, 12 @90fps).
//                0 = the top level → GPU held at max. This is the vendor-proven GPU-boost opcode;
//                the kgsl freq nodes are SELinux-read-blocked from shell so it can't be read back
//                live, but the perf HAL (which owns kgsl) applies it.
// ---- BUGFIX (XDA #236: "v2 only maxes GPU, CPU behaves same as v1.1/1.2") -------------------
// perf_lock_acq() takes a FLAT ARRAY OF (opcode, value) PAIRS. The old table tried to encode the
// msm_performance multi-core request as `0x63C5C000, <count>, <cpu>, <freq>, <cpu>, <freq>, ...`,
// which breaks that contract: the HAL read (0x63C5C000,16) and then parsed (1,2400000),
// (2,2400000) ... as opcode/value pairs. Opcodes 1..8 are not real resources, so everything after
// the first two pairs was garbage — which is exactly why only the GPU pair (0x42804000) took
// effect. Removed.
//
// The remaining reason the CPU still felt unboosted: 0x40800000 is cluster 0 only. On SM8850
// (8 Elite Gen 5) policy0 = cores 0-5 and policy6 = the PRIME pair (cores 6-7) — and the prime
// pair is what actually carries game frame work. Cluster index lives in the low nibble of byte 2
// of the opcode (cluster N = 0x408000N0), so cluster 1 = 0x40800010.
//
// Values are property-tunable so they can be validated/tuned on-device without a rebuild:
//   persist.sys.rm.gameperf.c0_mhz   (default 2496) - cluster 0 min freq, MHz
//   persist.sys.rm.gameperf.c1_mhz   (default 3000) - cluster 1 / prime min freq, MHz
//   persist.sys.rm.gameperf.gpu_lvl  (default 0)    - kgsl min power level (0 = top/highest freq)
// The perf HAL rounds each freq to the nearest supported OPP. Unknown opcodes are ignored by the
// HAL, so an inapplicable cluster opcode is harmless.
std::vector<int> BuildBoostArgs() {
  auto prop = [](const char* k, int def) {
    return android::base::GetIntProperty(k, def, 0, 6000);
  };
  const int c0 = prop("persist.sys.rm.gameperf.c0_mhz", 2496);
  const int c1 = prop("persist.sys.rm.gameperf.c1_mhz", 3000);
  const int gpu = prop("persist.sys.rm.gameperf.gpu_lvl", 0);
  std::vector<int> a;
  a.push_back(0x40800000); a.push_back(c0);   // cluster 0 (cores 0-5) min freq
  a.push_back(0x40800010); a.push_back(c1);   // cluster 1 (prime cores 6-7) min freq
  a.push_back(0x42804000); a.push_back(gpu);  // GPU min power level (0 = max)
  LOG(INFO) << "gameperfd: boost args c0=" << c0 << "MHz c1=" << c1 << "MHz gpu_lvl=" << gpu;
  return a;
}

using perf_lock_acq_t = int (*)(int handle, int duration, int list[], int numArgs);
using perf_lock_rel_t = int (*)(int handle);

perf_lock_acq_t g_acq = nullptr;
perf_lock_rel_t g_rel = nullptr;

// ---- Network affinity (NX123Dos: pin networking onto a dedicated core) --------------------
// The WiFi link is served by the QCA PCIe copy-engine IRQs ("pciN_wlan_ce_*"). Under game load,
// letting those land on the little cores (or the same prime cores rendering the game) adds packet
// jitter. Steer them + RPS to a dedicated gold core so packet handling isn't preempted.
//   NET_CORE_MASK: cpu5 (a gold core) — fast, and separate from the prime pair the game uses.
constexpr const char* kNetIrqMaskOn = "20";   // 0x20 = cpu5
constexpr const char* kNetIrqMaskOff = "ff";  // restore: all cpus
constexpr const char* kRpsMaskOn = "30";      // 0x30 = cpu4,5
constexpr const char* kRpsMaskOff = "0";
constexpr const char* kWlanIface = "wlan0";

void WriteFile(const std::string& path, const std::string& val) {
  std::ofstream f(path);
  if (f) f << val;
}

// Collect IRQ numbers whose /proc/interrupts action name contains "wlan".
std::vector<std::string> WlanIrqs() {
  std::vector<std::string> out;
  std::ifstream f("/proc/interrupts");
  std::string line;
  while (std::getline(f, line)) {
    if (line.find("wlan") == std::string::npos) continue;
    size_t colon = line.find(':');
    if (colon == std::string::npos) continue;
    std::string num = line.substr(0, colon);
    size_t s = num.find_first_not_of(" \t");
    if (s == std::string::npos) continue;
    num = num.substr(s);
    if (!num.empty() && num.find_first_not_of("0123456789") == std::string::npos) out.push_back(num);
  }
  return out;
}

void SetNetAffinity(bool on) {
  for (const auto& irq : WlanIrqs()) {
    WriteFile("/proc/irq/" + irq + "/smp_affinity", on ? kNetIrqMaskOn : kNetIrqMaskOff);
  }
  // RPS on every wlan rx queue.
  std::string qdir = std::string("/sys/class/net/") + kWlanIface + "/queues";
  DIR* d = opendir(qdir.c_str());
  if (d) {
    struct dirent* e;
    while ((e = readdir(d)) != nullptr) {
      std::string name = e->d_name;
      if (name.rfind("rx-", 0) == 0) {
        WriteFile(qdir + "/" + name + "/rps_cpus", on ? kRpsMaskOn : kRpsMaskOff);
      }
    }
    closedir(d);
  }
}

// ---- Fan + liquid cooling (RedMagic "Diablo"-style, per-game) ------------------------------
// Full cooling while a performance-mode game is foreground: persist.sys.fan.level 0..5 (5=full)
// and persist.sys.cooling.level 0..3 (3=full micropump). Setting these props fires the vendor_init
// redmagic_hw_arm.rc that does the real /sys/kernel/fan + /proc/driver/micropump writes (the
// thermal-safe OEM path; a coredomain/ksu process can't touch the vendor hardware nodes directly).
// Auto-fan (persist.sys.rm.fan_auto) is parked during the boost so its temp loop doesn't pull the
// level back down, then restored. Requires the module sepolicy.rule granting ksu set on rm_hw_prop.
std::string g_savedFan, g_savedCooling, g_savedFanAuto;

void CoolingBoost(bool on) {
  if (on) {
    g_savedFanAuto = android::base::GetProperty("persist.sys.rm.fan_auto", "");
    g_savedFan = android::base::GetProperty("persist.sys.fan.level", "0");
    g_savedCooling = android::base::GetProperty("persist.sys.cooling.level", "0");
    android::base::SetProperty("persist.sys.rm.fan_auto", "0");     // stop auto-fan fighting us
    android::base::SetProperty("persist.sys.fan.level", "5");       // full fan (FanTile maxLevel=5)
    android::base::SetProperty("persist.sys.cooling.level", "1");   // pump ON = full (piezo is on/off)
  } else {
    android::base::SetProperty("persist.sys.fan.level", g_savedFan.empty() ? "0" : g_savedFan);
    android::base::SetProperty("persist.sys.cooling.level",
                               g_savedCooling.empty() ? "0" : g_savedCooling);
    if (!g_savedFanAuto.empty()) {
      android::base::SetProperty("persist.sys.rm.fan_auto", g_savedFanAuto);
    }
  }
}

bool LoadPerfClient() {
  void* h = dlopen("libqti-perfd-client.so", RTLD_NOW);
  if (h == nullptr) {
    // Fall back to the absolute vendor path when the caller's linker namespace does not
    // search /vendor/lib64 by soname.
    h = dlopen("/vendor/lib64/libqti-perfd-client.so", RTLD_NOW);
  }
  if (h == nullptr) {
    LOG(ERROR) << "gameperfd: dlopen libqti-perfd-client.so failed: " << dlerror();
    return false;
  }
  g_acq = reinterpret_cast<perf_lock_acq_t>(dlsym(h, "perf_lock_acq"));
  g_rel = reinterpret_cast<perf_lock_rel_t>(dlsym(h, "perf_lock_rel"));
  if (g_acq == nullptr || g_rel == nullptr) {
    LOG(ERROR) << "gameperfd: dlsym perf_lock_acq/rel failed";
    return false;
  }
  return true;
}

}  // namespace

int main(int argc, char** argv) {
  if (!LoadPerfClient()) {
    return 1;
  }

  if (argc > 1 && std::string(argv[1]) == "test") {
    std::vector<int> args = BuildBoostArgs();
    int handle = g_acq(0, 30000, args.data(), static_cast<int>(args.size()));
    LOG(ERROR) << "gameperfd: TEST acquired perf lock handle=" << handle;
    sleep(28);
    if (handle > 0) g_rel(handle);
    LOG(ERROR) << "gameperfd: TEST released";
    return 0;
  }

  // testraw <opcodeHex> <value> [<opcodeHex> <value> ...] : acquire a raw perf lock for 25s.
  if (argc > 3 && std::string(argv[1]) == "testraw") {
    int args[32];
    int n = 0;
    for (int i = 2; i + 1 < argc && n < 32; i += 2) {
      args[n++] = (int)strtol(argv[i], nullptr, 0);
      args[n++] = (int)strtol(argv[i + 1], nullptr, 0);
    }
    int handle = g_acq(0, 25000, args, n);
    LOG(ERROR) << "gameperfd: TESTRAW handle=" << handle << " nargs=" << n;
    sleep(23);
    if (handle > 0) g_rel(handle);
    return 0;
  }

  int handle = 0;
  std::string last;
  while (true) {
    std::string v = android::base::GetProperty("persist.sys.power_mode_perf", "0");
    if (v != last) {
      if (v == "1" && handle <= 0) {
        // duration 0 = hold until explicitly released. Args are rebuilt per-acquire so a prop
        // change takes effect on the next game launch (no reboot needed while tuning).
        std::vector<int> args = BuildBoostArgs();
        handle = g_acq(0, 0, args.data(), static_cast<int>(args.size()));
        SetNetAffinity(true);
        CoolingBoost(true);
        LOG(INFO) << "gameperfd: boost ON, handle=" << handle;
      } else if (v != "1" && handle > 0) {
        g_rel(handle);
        SetNetAffinity(false);
        CoolingBoost(false);
        LOG(INFO) << "gameperfd: boost OFF";
        handle = 0;
      }
      last = v;
    }
    sleep(2);
  }
  return 0;
}
