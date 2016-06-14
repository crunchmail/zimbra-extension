package com.crunchmail.extension;

import com.zimbra.common.util.Log;
import com.zimbra.common.util.ZimbraLog;

public class Logger {

    private Log mLog;
    private boolean mDebug;

    public Logger() {
        this(false);
    }

    public Logger(boolean debug) {
        mLog = ZimbraLog.extensions;
        mDebug = debug;
    }

    private String formatter(String s) {
        return "Crunchmail - "+s;
    }

    public void trace(String s) {
        mLog.trace(formatter(s));
    }

    public void trace(String s, Object ... objects) {
        mLog.trace(formatter(s), objects);
    }

    public void debug(String s) {
        if (mDebug) {
            mLog.info(formatter(s));
        } else {
            mLog.debug(formatter(s));
        }
    }

    public void debug(String s, Object ... objects) {
        mLog.debug(formatter(s), objects);
    }

    public void info(String s) {
        mLog.info(formatter(s));
    }

    public void info(String s, Object ... objects) {
        mLog.info(formatter(s), objects);
    }

    public void warn(String s) {
        mLog.warn(formatter(s));
    }

    public void warn(String s, Object ... objects) {
        mLog.warn(formatter(s), objects);
    }

    public void warn(String s, Throwable e) {
        mLog.warn(formatter(s), e);
    }

    public void error(String s) {
        mLog.error(formatter(s));
    }

    public void error(String s, Object ... objects) {
        mLog.error(formatter(s), objects);
    }

    public void error(String s, Throwable e) {
        mLog.error(formatter(s), e);
    }

    public void fatal(String s) {
        mLog.fatal(formatter(s));
    }
}
