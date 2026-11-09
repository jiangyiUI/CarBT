package com.example.carbluetooth;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 简易落盘日志：每条日志同时写 logcat 和文件。
 * 路径：/sdcard/Android/data/<包名>/files/logs/btmonitor.log
 * 外部存储不可用时会自动写到内部存储 files/logs。
 * 容量：单文件 512KB，保留 1 个备份，总计最大约 1MB。
 */
public final class FileLogger {

    private static final long MAX_SIZE = 512 * 1024L; // 单文件 512KB
    private static final int MAX_BACKUP = 1;          // 保留 1 个备份
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA);

    private static File sLogFile;
    /** 日志总开关：控制是否落盘（logcat 不受影响）。默认 true 保证无开关 UI 时不中断。 */
    private static boolean sEnabled = true;

    private FileLogger() {
    }

    /** 设置日志落盘开关（UI 层调用） */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /** 当前是否落盘 */
    public static boolean isEnabled() {
        return sEnabled;
    }

    /** 不清理旧日志（Service、Activity 调用） */
    public static void init(Context context) {
        init(context, false);
    }

    /**
     * @param clearOldLogs true 时先删除上次残留的 btmonitor.log 及备份
     */
    public static void init(Context context, boolean clearOldLogs) {
        synchronized (LOCK) {
            // 每次 init 都读 prefs，确保开关状态和 UI 一致
            try {
                SharedPreferences p = context.getApplicationContext()
                        .getSharedPreferences("bt_prefs", Context.MODE_PRIVATE);
                sEnabled = p.getBoolean("log_print_enable", false);
            } catch (Exception ignored) { }

            if (sLogFile != null) {
                return;
            }
            File dir = context.getExternalFilesDir("logs");
            if (dir == null) {
                dir = new File(context.getFilesDir(), "logs");
            }
            if (!dir.exists()) {
                dir.mkdirs();
            }
            sLogFile = new File(dir, "btmonitor.log");

            if (clearOldLogs) {
                cleanDir(dir);
                // 上次若因外部存储未挂载写到内部，也一并清掉
                cleanDir(new File(context.getFilesDir(), "logs"));
            }

            if (sEnabled) {
                raw("I FileLogger: ==== 日志开始 pid=" + Process.myPid()
                        + " 开机已运行=" + (SystemClock.elapsedRealtime() / 1000) + "s ====");
            }
        }
    }

    public static void d(String tag, String msg) {
        if (!sEnabled) return;
        Log.d(tag, msg);
        write("D", tag, msg, null);
    }

    public static void i(String tag, String msg) {
        if (!sEnabled) return;
        Log.i(tag, msg);
        write("I", tag, msg, null);
    }

    public static void w(String tag, String msg) {
        if (!sEnabled) return;
        Log.w(tag, msg);
        write("W", tag, msg, null);
    }

    public static void e(String tag, String msg) {
        if (!sEnabled) return;
        Log.e(tag, msg);
        write("E", tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable tr) {
        if (!sEnabled) return;
        Log.e(tag, msg, tr);
        write("E", tag, msg, tr);
    }

    /** 兼容原先 Log.getStackTraceString 的调用 */
    public static String getStackTraceString(Throwable tr) {
        return Log.getStackTraceString(tr);
    }

    private static void write(String level, String tag, String msg, Throwable tr) {
        StringBuilder sb = new StringBuilder();
        sb.append(level).append(' ').append(tag).append(": ").append(msg);
        if (tr != null) {
            sb.append('\n').append(Log.getStackTraceString(tr));
        }
        raw(sb.toString());
    }

    private static void raw(String body) {
        if (!sEnabled) return; // 开关关了就不落盘（logcat 调用已在 d/i/w/e 里独立执行）
        synchronized (LOCK) {
            if (sLogFile == null) {
                return; // 未 init 时只走 logcat
            }
            try {
                rotateIfNeeded();
                FileWriter fw = new FileWriter(sLogFile, true);
                fw.write(FMT.format(new Date()) + " " + body + "\n");
                fw.flush();
                fw.close();
            } catch (Exception ignored) {
                // 写日志失败不能影响业务
            }
        }
    }

    /** 只删除日志文件，不碰目录里的其它文件 */
    private static void cleanDir(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith("btmonitor.log")) {
                f.delete();
            }
        }
    }

    /** 超过 512KB 时滚动：btmonitor.log -> btmonitor.log.1 */
    private static void rotateIfNeeded() {
        if (sLogFile.length() < MAX_SIZE) {
            return;
        }
        File last = new File(sLogFile.getAbsolutePath() + "." + MAX_BACKUP);
        if (last.exists()) {
            last.delete();
        }
        for (int i = MAX_BACKUP - 1; i >= 1; i--) {
            File src = new File(sLogFile.getAbsolutePath() + "." + i);
            if (src.exists()) {
                src.renameTo(new File(sLogFile.getAbsolutePath() + "." + (i + 1)));
            }
        }
        if (sLogFile.exists()) {
            sLogFile.renameTo(new File(sLogFile.getAbsolutePath() + ".1"));
        }
    }
}