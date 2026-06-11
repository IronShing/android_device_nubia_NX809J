// goodix_calib_cb — like goodix_calib, but registers an IGoodixFingerprintDaemonCallback
// (setNotify, txn=1) BEFORE firing the no-chart cal (sendCommand, txn=2, cmd=5634),
// so we capture the asynchronous result struct the HAL delivers via
// notifyNoChartCaliAndPerformanceTest (~224 bytes). That struct carries the
// pass/fail + per-stage amp/tcode that tell us whether the cal failed validation
// (e.g. UDFPS illumination not active) or amp/tcode==0 (sensor TX not driven).
//
// The callback's onTransact is a GENERIC parcel dumper: it prints every 32-bit
// word of whatever the daemon sends, for any transaction code — so we don't need
// the exact notify method layout up front; we decode the dump afterwards.
//
// Usage: goodix_calib_cb [cmd_int=5634] [wait_secs=10]

#include <android/binder_auto_utils.h>
#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <android/binder_parcel.h>
#include <android/binder_process.h>
#include <android/binder_status.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>

static const char* kDaemonInst =
    "vendor.goodix.hardware.biometrics.fingerprint.IGoodixFingerprintDaemon/default";
static const char* kDaemonDesc =
    "vendor.goodix.hardware.biometrics.fingerprint.IGoodixFingerprintDaemon";
static const char* kCbDesc =
    "vendor.goodix.hardware.biometrics.fingerprint.IGoodixFingerprintDaemonCallback";

static const uint32_t TXN_SETNOTIFY   = 1;  // verified from BpGoodixFingerprintDaemon::setNotify
static const uint32_t TXN_SENDCOMMAND = 2;  // verified from BpGoodixFingerprintDaemon::sendCommand

// inert stubs for the daemon proxy class
static void* dOnCreate(void* a) { return a; }
static void dOnDestroy(void*) {}
static binder_status_t dOnTransact(AIBinder*, transaction_code_t, const AParcel*, AParcel*) {
  return STATUS_UNKNOWN_TRANSACTION;
}

// callback class: dump the entire incoming parcel as 32-bit words + bytes
static void* cbOnCreate(void* a) { return a; }
static void cbOnDestroy(void*) {}
static binder_status_t cbOnTransact(AIBinder*, transaction_code_t code,
                                    const AParcel* in, AParcel* /*out*/) {
  int32_t size = AParcel_getDataSize(in);
  printf("\n========== [CALLBACK] code=%u  dataSize=%d ==========\n", code, size);
  AParcel_setDataPosition(in, 0);
  int i = 0;
  while (true) {
    int32_t pos = AParcel_getDataPosition(in);
    if (pos >= size) break;
    int32_t w = 0;
    if (AParcel_readInt32(in, &w) != STATUS_OK) break;
    uint32_t u = (uint32_t)w;
    // also show the 4 bytes (little-endian) for struct decoding
    printf("  w[%03d] @%-5d 0x%08x  int=%-11d  bytes= %02x %02x %02x %02x\n",
           i++, pos, u, w, u & 0xff, (u >> 8) & 0xff, (u >> 16) & 0xff, (u >> 24) & 0xff);
  }
  printf("========== [CALLBACK end] ==========\n");
  fflush(stdout);
  return STATUS_OK;
}

int main(int argc, char** argv) {
  const int32_t cmd = (argc >= 2) ? (int32_t)strtol(argv[1], nullptr, 0) : 5634;
  const int waitSecs = (argc >= 3) ? atoi(argv[2]) : 10;

  ABinderProcess_setThreadPoolMaxThreadCount(1);
  ABinderProcess_startThreadPool();

  // our callback object
  AIBinder_Class* cbClass = AIBinder_Class_define(kCbDesc, cbOnCreate, cbOnDestroy, cbOnTransact);
  AIBinder* cb = AIBinder_new(cbClass, nullptr);
  if (!cb) { fprintf(stderr, "FATAL: AIBinder_new(callback) failed\n"); return 2; }

  // the daemon
  ndk::SpAIBinder daemon(AServiceManager_waitForService(kDaemonInst));
  if (!daemon.get()) { fprintf(stderr, "FATAL: daemon service not found\n"); return 3; }
  AIBinder_Class* dClass = AIBinder_Class_define(kDaemonDesc, dOnCreate, dOnDestroy, dOnTransact);
  if (!AIBinder_associateClass(daemon.get(), dClass)) {
    fprintf(stderr, "FATAL: associateClass(daemon) failed\n"); return 4;
  }

  // setNotify(cb)
  {
    AParcel* in = nullptr;
    if (AIBinder_prepareTransaction(daemon.get(), &in) != STATUS_OK) { fprintf(stderr, "prep setNotify failed\n"); return 5; }
    AParcel_writeStrongBinder(in, cb);
    AParcel* out = nullptr;
    binder_status_t st = AIBinder_transact(daemon.get(), TXN_SETNOTIFY, &in, &out, 0);
    printf("[setNotify] transact=%d (%s)\n", st, st == STATUS_OK ? "OK" : "ERR");
    AStatus* s = nullptr;
    if (out && AParcel_readStatusHeader(out, &s) == STATUS_OK && s) {
      printf("[setNotify] Status exception=%d serviceSpecific=%d\n",
             AStatus_getExceptionCode(s), AStatus_getServiceSpecificError(s));
      AStatus_delete(s);
    }
  }

  // sendCommand(cmd, [])
  {
    AParcel* in = nullptr;
    if (AIBinder_prepareTransaction(daemon.get(), &in) != STATUS_OK) { fprintf(stderr, "prep sendCommand failed\n"); return 6; }
    AParcel_writeInt32(in, cmd);
    int8_t dummy = 0;
    AParcel_writeByteArray(in, &dummy, 0);  // empty payload
    AParcel* out = nullptr;
    binder_status_t st = AIBinder_transact(daemon.get(), TXN_SENDCOMMAND, &in, &out, 0);
    printf("[sendCommand cmd=%d] transact=%d (%s)\n", cmd, st, st == STATUS_OK ? "OK" : "ERR");
    AStatus* s = nullptr;
    if (out && AParcel_readStatusHeader(out, &s) == STATUS_OK && s) {
      printf("[sendCommand] Status exception=%d serviceSpecific=%d\n",
             AStatus_getExceptionCode(s), AStatus_getServiceSpecificError(s));
      AStatus_delete(s);
    }
  }

  printf("[*] waiting %ds for the async notify callback...\n", waitSecs);
  fflush(stdout);
  sleep(waitSecs);
  printf("[*] done.\n");
  return 0;
}
