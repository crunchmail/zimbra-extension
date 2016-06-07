package com.crunchmail.extension;

import com.zimbra.common.util.Log;
import com.zimbra.common.util.ZimbraLog;

public class Logger {

    private Log logger;

    public Logger() {
        logger = ZimbraLog.extensions;
    }

    private String formatter(String s) {
        return "Crunchmail - "+s;
    }

    public void trace(String s, Object ... objects) {
        logger.trace(formatter(s), objects);
    }

    public void debug(String s, Object ... objects) {
        logger.debug(formatter(s), objects);
    }

    public void info(String s, Object ... objects) {
        logger.info(formatter(s), objects);
    }

    public void warn(String s, Object ... objects) {
        logger.warn(formatter(s), objects);
    }

    public void error(String s, Object ... objects) {
        logger.error(formatter(s), objects);
    }

    public void fatal(String s, Object ... objects) {
        logger.fatal(formatter(s), objects);
    }
}
