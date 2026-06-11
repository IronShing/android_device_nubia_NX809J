// goodix_calib — minimal raw libbinder_ndk client for the ZTE/Goodix
// fingerprint daemon, used to trigger the on-device no-chart calibration
// (factoryNoChartCalibrateAndPerformanceTest) without the stock factory app,
// which is absent on LineageOS.
//
// The interface is a prebuilt vendor AIDL with no in-tree .aidl / generated
// headers, so we marshal the transaction by hand:
//
//   IGoodixFingerprintDaemon::sendCommand(int cmd, byte[] payload, out CommandResult)
//
// Transaction code, the cmd int, and the payload bytes are all supplied on the
// command line because they must come from RE of the daemon's sendCommand
// dispatch (or the stock factory APK). Nothing is hard-coded that we haven't
// verified.
//
// Usage:
//   goodix_calib <txn_code> <cmd_int> [payload_hex]
//     txn_code    decimal binder transaction code = FIRST_CALL_TRANSACTION(1)
//                 + zero-based method index of sendCommand in the AIDL.
//     cmd_int     the first sendCommand argument (decimal or 0x-hex).
//     payload_hex optional byte[] payload: "aa,bb,cc" / "aa bb cc" / "aabbcc".
//
// Verification of the *effect* is out-of-band: after a successful call, check
//   ls /data/vendor/goodix/         (expect cali_{0_0,2,3,4}.so + cali_data_extra_0.so)
// and watch logcat for [GF_HAL] cali_state -> 1. This tool only reports whether
// the binder call itself was accepted and the Status the daemon returned.
//
// Rollback: the no-chart path writes only /data/vendor/goodix/*; persist is
// never touched. To revert: rm /data/vendor/goodix/cali_*.so

#include <android/binder_auto_utils.h>
#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>

#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

static const char* kInstance =
    "vendor.goodix.hardware.biometrics.fingerprint.IGoodixFingerprintDaemon/default";
static const char* kDescriptor =
    "vendor.goodix.hardware.biometrics.fingerprint.IGoodixFingerprintDaemon";

// Client-only proxy: associateClass needs a class with the matching descriptor,
// but we never serve transactions, so the callbacks are inert stubs.
static void* OnCreate(void* args) { return args; }
static void OnDestroy(void* /*userData*/) {}
static binder_status_t OnTransact(AIBinder* /*b*/, transaction_code_t /*code*/,
                                  const AParcel* /*in*/, AParcel* /*out*/) {
  return STATUS_UNKNOWN_TRANSACTION;
}

// Parse "aa,bb,cc" / "aa bb cc" / packed "aabbcc" into bytes.
static std::vector<uint8_t> ParseHex(const char* s) {
  std::string in(s ? s : "");
  std::vector<uint8_t> out;
  bool hasDelim = in.find_first_of(", \t:") != std::string::npos;
  if (!hasDelim && in.size() % 2 == 0) {
    for (size_t i = 0; i + 1 < in.size(); i += 2) {
      std::string byte = in.substr(i, 2);
      out.push_back(static_cast<uint8_t>(strtol(byte.c_str(), nullptr, 16)));
    }
    return out;
  }
  std::string cur;
  auto flush = [&]() {
    if (!cur.empty()) {
      out.push_back(static_cast<uint8_t>(strtol(cur.c_str(), nullptr, 16)));
      cur.clear();
    }
  };
  for (char c : in) {
    if (isxdigit(static_cast<unsigned char>(c)) ||
        (c == 'x' && cur == "0")) {
      cur.push_back(c);
    } else {
      flush();
    }
  }
  flush();
  return out;
}

// Allocator so we can read an out byte[] (the CommandResult may embed one).
static bool ByteArrayAllocator(void* arrayData, int32_t length, int8_t** outBuffer) {
  auto* v = static_cast<std::vector<int8_t>*>(arrayData);
  if (length < 0) {
    *outBuffer = nullptr;
    return true;
  }
  v->resize(length);
  *outBuffer = v->data();
  return true;
}

int main(int argc, char** argv) {
  if (argc < 3) {
    fprintf(stderr,
            "usage: %s <txn_code> <cmd_int> [payload_hex]\n"
            "  txn_code = 1 + zero-based method index of sendCommand\n",
            argv[0]);
    return 64;
  }
  const uint32_t txn = static_cast<uint32_t>(strtoul(argv[1], nullptr, 0));
  const int32_t cmd = static_cast<int32_t>(strtol(argv[2], nullptr, 0));
  std::vector<uint8_t> payload = (argc >= 4) ? ParseHex(argv[3]) : std::vector<uint8_t>{};

  printf("[goodix_calib] instance=%s\n", kInstance);
  printf("[goodix_calib] txn=%u cmd=%d payload=%zu bytes\n", txn, cmd, payload.size());

  ndk::SpAIBinder binder(AServiceManager_waitForService(kInstance));
  if (binder.get() == nullptr) {
    fprintf(stderr, "[goodix_calib] FATAL: service not found on /dev/binder\n");
    return 2;
  }

  AIBinder_Class* clazz =
      AIBinder_Class_define(kDescriptor, OnCreate, OnDestroy, OnTransact);
  if (!AIBinder_associateClass(binder.get(), clazz)) {
    fprintf(stderr,
            "[goodix_calib] FATAL: associateClass failed — descriptor mismatch "
            "(remote is not %s?)\n",
            kDescriptor);
    return 3;
  }

  AParcel* in = nullptr;
  binder_status_t st = AIBinder_prepareTransaction(binder.get(), &in);
  if (st != STATUS_OK) {
    fprintf(stderr, "[goodix_calib] prepareTransaction failed: %d\n", st);
    return 4;
  }
  // sendCommand args, in declaration order: int cmd, byte[] payload.
  AParcel_writeInt32(in, cmd);
  AParcel_writeByteArray(in, reinterpret_cast<const int8_t*>(payload.data()),
                         static_cast<int32_t>(payload.size()));

  AParcel* out = nullptr;
  st = AIBinder_transact(binder.get(), txn, &in, &out, 0);
  printf("[goodix_calib] transact() binder_status=%d (%s)\n", st,
         st == STATUS_OK ? "OK" : "ERR");
  if (st != STATUS_OK) {
    fprintf(stderr,
            "[goodix_calib] transact failed — wrong txn code, or daemon rejected "
            "the call. Try a different txn_code.\n");
    return 5;
  }

  // Reply: AIDL NDK writes a Status header first.
  AStatus* status = nullptr;
  binder_status_t rs = AParcel_readStatusHeader(out, &status);
  if (rs == STATUS_OK && status != nullptr) {
    const char* msg = AStatus_getMessage(status);
    printf("[goodix_calib] Status: exception=%d serviceSpecific=%d status=%d msg=%s\n",
           AStatus_getExceptionCode(status), AStatus_getServiceSpecificError(status),
           AStatus_getStatus(status), msg ? msg : "(none)");
    bool ok = AStatus_isOk(status);
    AStatus_delete(status);
    if (!ok) {
      fprintf(stderr, "[goodix_calib] daemon returned non-OK status\n");
      // still fall through to dump any out data
    }
  } else {
    printf("[goodix_calib] no Status header (rs=%d) — dumping raw reply\n", rs);
  }

  // Best-effort: read the out CommandResult. Layout is unverified, so try the
  // common {int result; byte[] data} shape and report whatever parses.
  int32_t resultCode = 0;
  if (AParcel_readInt32(out, &resultCode) == STATUS_OK) {
    printf("[goodix_calib] CommandResult.int0 = %d\n", resultCode);
    std::vector<int8_t> data;
    if (AParcel_readByteArray(out, &data, ByteArrayAllocator) == STATUS_OK) {
      printf("[goodix_calib] CommandResult.bytes = %zu\n", data.size());
      size_t n = data.size() < 64 ? data.size() : 64;
      printf("[goodix_calib] first %zu bytes:", n);
      for (size_t i = 0; i < n; i++) printf(" %02x", static_cast<uint8_t>(data[i]));
      printf("%s\n", data.size() > n ? " ..." : "");
    }
  }

  printf("[goodix_calib] done. Now check: ls -la /data/vendor/goodix/  and re-run enroll.\n");
  return 0;
}
