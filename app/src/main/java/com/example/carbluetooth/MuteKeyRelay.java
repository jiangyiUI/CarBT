package com.example.carbluetooth;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.lang.reflect.Method;

/**
 * 播放/暂停劫持。
 *
 * 背景：车机方向盘的静音键会被安波福中间件先吃掉（不把按键分发给应用），
 * 但会把音乐流/主静音置为静音。CarLife 的播放/暂停快捷键配置成 85，
 * 所以这里轮询静音状态，一旦检测到“刚刚被静音”就：
 * 1. 往系统注入一次真实的 KEYCODE_MEDIA_PLAY_PAUSE（等价 adb shell input keyevent 85）；
 * 2. 立即把静音解除，声音马上恢复，表现为“按静音键 = 暂停/继续”。
 *
 * 为避免误伤其它播放器（比如用户真的想静音本地音乐），
 * 只有当绑定的互联应用（CarLife）处于前台时才执行劫持。
 */
public class MuteKeyRelay {

    private static final String TAG = "MuteKeyRelay";

    /** 静音巡检间隔（毫秒），40ms 与单独的桥接 App 保持一致 */
    private static final long INTERVAL_MS = 40;

    /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
    private static final int INJECT_MODE_ASYNC = 2;

    /** 前台应用包名提供者（复用 ForegroundService 的 REAL_GET_TASKS 查询） */
    public interface ForegroundPackageProvider {
        String getForegroundPackage();
    }

    private final AudioManager audioManager;
    private final SharedPreferences prefs;
    private final ForegroundPackageProvider foregroundProvider;

    private HandlerThread thread;
    private Handler handler;

    private Method methodIsMasterMute;
    private Method methodSetMasterMute;

    /** 上一次巡检到的静音状态：只在“未静音 -> 静音”的上升沿触发一次 */
    private boolean lastMuted;
    private boolean running;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (prefs.getBoolean(MainActivity.KEY_HIJACK_SWITCH, true)) {
                boolean muted = detectMuted();
                if (muted && !lastMuted) {
                    onMutePressed();
                }
                lastMuted = muted;
            } else {
                // 开关关闭期间同步状态，避免再次打开时误判成上升沿
                lastMuted = detectMuted();
            }
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    public MuteKeyRelay(Context context, ForegroundPackageProvider provider) {
        Context appContext = context.getApplicationContext();
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        prefs = appContext.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE);
        foregroundProvider = provider;

        // 车机 ROM 对 AudioManager 的部分方法做了隐藏处理，统一用反射拿方法
        try {
            methodIsMasterMute = AudioManager.class.getMethod("isMasterMute");
        } catch (Throwable ignored) {
        }
        try {
            // 真实签名是 setMasterMute(boolean mute, int flags)
            methodSetMasterMute = AudioManager.class.getMethod(
                    "setMasterMute", boolean.class, int.class);
        } catch (Throwable ignored) {
        }
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new HandlerThread("mute-key-relay");
        thread.start();
        handler = new Handler(thread.getLooper());
        // 记录初始静音状态，避免服务启动时误触发一次
        lastMuted = detectMuted();
        handler.post(pollTask);
        FileLogger.i(TAG, "播放/暂停劫持已启动，当前静音状态=" + lastMuted);
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        handler = null;
        FileLogger.i(TAG, "播放/暂停劫持已停止");
    }

    // ==================== 触发 ====================

    private void onMutePressed() {
        String carlifePkg = prefs.getString(MainActivity.KEY_CARLIFE_PKG, null);
        String foreground = foregroundProvider.getForegroundPackage();
        boolean carlifeForeground = carlifePkg != null && carlifePkg.equals(foreground);

        if (!carlifeForeground) {
            // 不在互联应用里，不劫持也不解除静音，尊重用户原本的静音操作
            return;
        }

        FileLogger.i(TAG, "检测到静音键，前台=" + foreground + "，注入播放/暂停键");
        injectPlayPause();
        clearMutes();
    }

    /** 注入真实的媒体播放/暂停键（等价 adb shell input keyevent 85） */
    private void injectPlayPause() {
        try {
            // InputManager 不在公开 SDK 里，只能按类名反射；getInstance() 是隐藏的静态方法
            Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
            Object inputManager = inputManagerClass.getMethod("getInstance").invoke(null);
            if (inputManager == null) {
                FileLogger.e(TAG, "获取 InputManager 失败");
                return;
            }
            Method inject = inputManagerClass.getMethod(
                    "injectInputEvent", InputEvent.class, int.class);
            inject.invoke(inputManager, mediaKeyEvent(KeyEvent.ACTION_DOWN), INJECT_MODE_ASYNC);
            inject.invoke(inputManager, mediaKeyEvent(KeyEvent.ACTION_UP), INJECT_MODE_ASYNC);
        } catch (Throwable t) {
            FileLogger.e(TAG, "injectInputEvent 失败", t);
        }
    }

    private KeyEvent mediaKeyEvent(int action) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
        event.setSource(InputDevice.SOURCE_KEYBOARD);
        return event;
    }

    // ==================== 静音状态 ====================

    /**
     * 车机的 ADJUST_TOGGLE_MUTE 只会把“当前音频流”（音乐流）置为静音，
     * 不一定置位主静音；所以主静音 + STREAM_MUSIC 任一为真就算静音。
     * 不能遍历所有流（系统按铃声模式静音的 SYSTEM/RING/NOTIFICATION 会误判）。
     */
    private boolean detectMuted() {
        if (readMasterMute()) {
            return true;
        }
        try {
            return audioManager != null && audioManager.isStreamMute(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean readMasterMute() {
        if (methodIsMasterMute == null || audioManager == null) {
            return false;
        }
        try {
            Object result = methodIsMasterMute.invoke(audioManager);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取消主静音 + 音乐流静音，让声音立刻恢复 */
    private void clearMutes() {
        try {
            if (methodSetMasterMute != null) {
                methodSetMasterMute.invoke(audioManager, false, 0);
            }
        } catch (Throwable t) {
            FileLogger.e(TAG, "setMasterMute 调用失败: " + t);
        }
        try {
            if (audioManager != null && audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_UNMUTE, 0);
            }
        } catch (Throwable t) {
            FileLogger.e(TAG, "取消音乐流静音失败: " + t);
        }
    }
}
