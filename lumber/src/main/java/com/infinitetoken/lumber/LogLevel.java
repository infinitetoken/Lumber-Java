package com.infinitetoken.lumber;

/** Lumber's log levels. */
public enum LogLevel {
    /** Detail for debugging. */
    DEBUG(0),
    /** Normal operation. */
    INFO(1),
    /** Something unexpected that the program recovered from. */
    WARN(2),
    /** Something failed. */
    ERROR(3),
    /** The program can't go on. */
    FATAL(4);

    private final int value;

    LogLevel(int value) {
        this.value = value;
    }

    /**
     * The level's number in Lumber's API, which the server stores in the clear so the app can filter by level and push
     * alerts. Never change these.
     *
     * @return 0 for DEBUG up to 4 for FATAL
     */
    public int value() {
        return value;
    }
}
