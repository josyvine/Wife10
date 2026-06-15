package com.wife.app;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class WifeLogger {
    private static final String TAG = "WifeLogger";
    private static final String DIRECTORY_NAME = "Wife Debug report";
    private static final String FILE_NAME = "wife_debug_log.txt";

    public static void init(Context context) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            log("UncaughtCrash", "Unhandled exception occurred on thread: " + thread.getName(), throwable);
            System.exit(2);
        });
        log("System", "Wife Logging Engine Initialized successfully.");
    }

    public static synchronized void log(String tag, String message) {
        log(tag, message, null);
    }

    public static synchronized void log(String tag, String message, Throwable throwable) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        String deviceModel = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        String threadName = Thread.currentThread().getName();

        StringBuilder sb = new StringBuilder();
        sb.append("[").append(timestamp).append("] ");
        sb.append("[").append(deviceModel).append("] ");
        sb.append("[").append(threadName).append("] ");
        sb.append("[").append(tag).append("]: ");
        sb.append(message).append("\n");

        if (throwable != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            sb.append(sw.toString()).append("\n");
        }

        if (throwable != null) {
            Log.e(tag, message, throwable);
        } else {
            Log.d(tag, message);
        }

        try {
            File rootDir = Environment.getExternalStorageDirectory();
            File debugDir = new File(rootDir, DIRECTORY_NAME);
            if (!debugDir.exists()) {
                boolean created = debugDir.mkdirs();
                if (!created) {
                    Log.e(TAG, "Failed to create public local debug directory: " + debugDir.getAbsolutePath());
                    return;
                }
            }

            File logFile = new File(debugDir, FILE_NAME);
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write(sb.toString());
                writer.flush();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error writing logs to local storage: " + e.getMessage());
        }
    }
}