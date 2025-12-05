package nl.paultervoort.thermaldetector;

import android.util.Log;

import com.flir.thermalsdk.log.ThermalLog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Helper for logging to the FLIR thermal log. Defaults to the standard logger if thermal log not enabled.
 */
class LogHelper {
    private static final String LOG_PREFIX_FORMAT = "[%s] [atlas_logger_fallback] [%s] ";
    private static final String DATE_FORMAT_STRING = "yyyy-MM-dd HH:mm:ss.SSS";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat(DATE_FORMAT_STRING, Locale.ROOT);

    private static boolean thermalLogAvailable = false;

    /**
     * Start logging to the thermal log
     */
    public static void enableThermalLog() {
        LogHelper.thermalLogAvailable = true;
    }

    /**
     * Prints an ERROR message on the LogCat.
     * @param tag LogCat tag
     * @param message LogCat message
     */
    public static void e(String tag, String message) {
        if (tag == null || message == null) {
            return;
        }

        if (LogHelper.thermalLogAvailable) {
            ThermalLog.e(tag, message);
        } else {
            Log.e(tag, getLogPrefix(ThermalLog.LogLevel.ERROR) + message);
        }
    }

    /**
     * Prints an INFO message on the LogCat.
     * @param tag LogCat tag
     * @param message LogCat message
     */
    public static void i(String tag, String message) {
        if (tag == null || message == null) {
            return;
        }

        if (LogHelper.thermalLogAvailable) {
            ThermalLog.i(tag, message);
        } else {
            Log.i(tag, getLogPrefix(ThermalLog.LogLevel.INFO) + message);
        }
    }

    private static String getLogPrefix(ThermalLog.LogLevel level) {
        return String.format(LOG_PREFIX_FORMAT, DATE_FORMAT.format(new Date()), level.toString().toLowerCase());
    }
}
