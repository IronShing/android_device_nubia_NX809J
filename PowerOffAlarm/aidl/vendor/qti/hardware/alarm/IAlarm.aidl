package vendor.qti.hardware.alarm;

// ABI of the stock vendor.qti.hardware.alarm.IAlarm/default (V1). Transaction
// codes follow declaration order and MUST stay: cancelAlarm=1, getAlarm=2,
// getRtcTime=3, setAlarm=4. All times are RTC seconds, not wall-clock millis.
interface IAlarm {
    /** Clear the PMIC RTC alarm. 0 on success. */
    int cancelAlarm();
    /** Currently programmed RTC alarm, in RTC seconds. */
    long getAlarm();
    /** Current RTC counter, in seconds. */
    long getRtcTime();
    /** Program the RTC alarm to an absolute RTC second. 0 on success. */
    int setAlarm(long rtcSeconds);
}
