package com.example.carbluetooth;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

public class ForegroundService extends Service {

    private static final String TAG = "BtMonitorService";
    private static final String CHANNEL_ID = "bt_monitor_channel";
    private static final int NOTIFICATION_ID = 1;

    // 蓝牙扫描模式常量（BluetoothAdapter 中被 @hide 的常量）
    private static final int SCAN_MODE_CONNECTABLE = 21;
    private static final int SCAN_MODE_CONNECTABLE_DISCOVERABLE = 23;

    private static final long DISCOVERABLE_INTERVAL_MS = 100 * 1000; // 可发现状态维持周期
    private static final long WATCHDOG_INTERVAL_MS = 1 * 1000;      // 状态机评估周期
    private static final long CONNECT_RETRY_INTERVAL_MS = 20 * 1000; // 主动连接限频
    private static final long PROFILE_RETRY_INTERVAL_MS = 30 * 1000; // Profile 代理补齐周期
    private static final long ENABLE_BT_RETRY_INTERVAL_MS = 30 * 1000; // 开蓝牙限频

    // PROFILE_A2DP_SINK / HEADSET_CLIENT 在公开 SDK 里是 @hide 常量（编译期找不到符号），
    // 直接用它们在 AOSP 中的数值。运行时系统签名应用可以正常请求这两个 Profile。
    private static final int PROFILE_A2DP_SINK = 11;
    private static final int PROFILE_HEADSET_CLIENT = 16;

    // ===== 热点自检 =====
    private static final int WIFI_AP_STATE_ENABLING = 12;
    private static final int WIFI_AP_STATE_ENABLED  = 13;
    /** WifiService 就绪探测周期：每 2 秒查一次 getWifiApConfiguration() 能不能拿到 */
    private static final long HOTSPOT_PROBE_INTERVAL_MS = 2 * 1000;
    /** 兜底超时：即使探测一直拿不到配置，到这个时间点也强制 try 一次开热点 */
    private static final long HOTSPOT_FIRST_CHECK_DELAY_MS = 15 * 1000;
    /** 首次尝试开启热点后，等待这个时间再复查一次是否真的起来了 */
    private static final long HOTSPOT_VERIFY_DELAY_MS = 5 * 1000;

    /** startTethering 崩溃保护标记：尝试过但进程没活下来则永久置位 */
    private static final String KEY_TETHER_CRASHED = "tether_crashed";

    private BluetoothAdapter bluetoothAdapter;
    private SharedPreferences prefs;
    private final Handler handler = new Handler();

    private boolean discoverableLoopActive = false;
    private boolean connectLoopActive = false;
    /** 本次开机后绑定手机是否发生过断连（开机不广播） */
    private boolean disconnectSeen = false;

    /** 播放/暂停劫持：检测静音键并注入媒体键 */
    private MuteKeyRelay muteKeyRelay;

    // 蓝牙 Profile 代理：车机通常是 A2DP Sink / HFP HF，手机是 Source / AG
    private BluetoothProfile a2dpProxy;
    private BluetoothProfile a2dpSinkProxy;
    private BluetoothProfile headsetProxy;
    private BluetoothProfile headsetClientProxy;
    private long lastProfileRequestTime = 0L;
    private long lastEnableAttemptTime = 0L;

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    switch (profile) {
                        case BluetoothProfile.A2DP:
                            a2dpProxy = proxy;
                            FileLogger.i(TAG, "A2DP(Source) 代理已就绪");
                            break;
                        case PROFILE_A2DP_SINK:
                            a2dpSinkProxy = proxy;
                            FileLogger.i(TAG, "A2DP_SINK 代理已就绪");
                            break;
                        case BluetoothProfile.HEADSET:
                            headsetProxy = proxy;
                            FileLogger.i(TAG, "HFP(AG) 代理已就绪");
                            break;
                        case PROFILE_HEADSET_CLIENT:
                            headsetClientProxy = proxy;
                            FileLogger.i(TAG, "HFP(HF) 代理已就绪");
                            break;
                        default:
                            break;
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    switch (profile) {
                        case BluetoothProfile.A2DP:
                            a2dpProxy = null;
                            break;
                        case PROFILE_A2DP_SINK:
                            a2dpSinkProxy = null;
                            break;
                        case BluetoothProfile.HEADSET:
                            headsetProxy = null;
                            break;
                        case PROFILE_HEADSET_CLIENT:
                            headsetClientProxy = null;
                            break;
                        default:
                            break;
                    }
                    FileLogger.w(TAG, "Profile 代理断开：" + profile);
                }
            };

    /** 可发现广播维持任务 */
    private final Runnable discoverableRunnable = new Runnable() {
        @Override
        public void run() {
            setScanModeByReflection(SCAN_MODE_CONNECTABLE_DISCOVERABLE, 0);
            handler.postDelayed(this, DISCOVERABLE_INTERVAL_MS);
        }
    };

    /** 主动连接任务：每 20 秒尝试一次 */
    private final Runnable connectRunnable = new Runnable() {
        @Override
        public void run() {
            attemptConnectBoundPhone();
            handler.postDelayed(this, CONNECT_RETRY_INTERVAL_MS);
        }
    };

    /** 看门狗：每 15 秒评估一次完整状态机 */
    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            evaluateState();
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    /**
     * 热点自动开启：
     * 1. BOOT_COMPLETED 后立即开始每 2 秒探测一次 WifiService 是否就绪
     *    （getWifiApConfiguration() 能拿到非 null 配置就算就绪）
     * 2. 一旦就绪，立刻触发首次开启尝试，之后每 5 秒复查一次，
     *    最多重试 5 次，成功或超过上限后永久停掉
     * 3. 15 秒兜底：即使探测一直没拿到配置，到点也强制 try 一次
     * 4. 后续用户手动关了热点不再自动开回来
     */
    private static final int HOTSPOT_MAX_RETRY = 5;
    private int hotspotRetryCount = 0;
    /** 标记：已经触发过首次开启尝试（不管是探测发现还是兜底超时） */
    private boolean hotspotAttemptStarted = false;

    /** 开机立即启动的 WifiService 就绪探测器 */
    private final Runnable hotspotProbeRunnable = new Runnable() {
        @Override
        public void run() {
            if (hotspotAttemptStarted) return; // 已经触发过了，停掉探测

            boolean switchOn = prefs.getBoolean(MainActivity.KEY_HOTSPOT_SWITCH, true);
            if (!switchOn) {
                // 用户已经关了自动开关，没必要探测了
                return;
            }

            boolean apOn = isHotspotEnabled();
            if (apOn) {
                FileLogger.i(TAG, "热点探测：发现热点已开启，无需尝试");
                hotspotAttemptStarted = true;
                return;
            }

            // 探测 WifiService 是否就绪：getWifiApConfiguration 能拿到非 null 配置
            WifiConfiguration cfg = tryGetWifiApConfig();
            if (cfg != null) {
                FileLogger.i(TAG, "热点探测：WifiService 已就绪（拿到配置 SSID="
                        + cfg.SSID + "），立即触发首次开启");
                hotspotAttemptStarted = true;
                handler.post(hotspotRunnable);
                return;
            }

            // 还没就绪，2 秒后再查一次
            handler.postDelayed(this, HOTSPOT_PROBE_INTERVAL_MS);
        }
    };

    private final Runnable hotspotRunnable = new Runnable() {
        @Override
        public void run() {
            boolean switchOn = prefs.getBoolean(MainActivity.KEY_HOTSPOT_SWITCH, true);
            boolean apOn = isHotspotEnabled();
            FileLogger.i(TAG, "热点自检：开关=" + switchOn
                    + "，热点已开启=" + apOn
                    + "，已重试=" + hotspotRetryCount + "/" + HOTSPOT_MAX_RETRY);

            if (!switchOn) {
                FileLogger.i(TAG, "用户关闭了自动打开功能，跳过");
                return;
            }
            if (apOn) {
                FileLogger.i(TAG, "热点已开启，停止重试（本次开机到此为止）");
                return;
            }
            if (hotspotRetryCount >= HOTSPOT_MAX_RETRY) {
                FileLogger.w(TAG, "已达最大重试次数 " + HOTSPOT_MAX_RETRY
                        + "，放弃自动开启，请在车机设置里手动开一次热点");
                return;
            }

            hotspotRetryCount++;
            FileLogger.i(TAG, "热点未开启，第 " + hotspotRetryCount
                    + " 次尝试自动打开");
            enableHotspotByReflection();
            handler.postDelayed(this, HOTSPOT_VERIFY_DELAY_MS);
        }
    };

    // ==================== 生命周期 ====================

    @Override
    public void onCreate() {
        super.onCreate();

        // 初始化落盘日志（Service 不清理，App 进程重启时保留当次开机日志）
        FileLogger.init(this);
        // 进程级崩溃落盘：把闪退堆栈写进 btmonitor.log，否则车机上看不到
        installCrashLogger();

        // 解除系统隐藏 API 限制：热点开关是 @hide 接口，部分 ROM 会拦截反射调用
        forceHiddenApiExemptions();

        prefs = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(btStateReceiver, filter);

        // 尝试获取四个方向的 Profile 代理
        ensureProfileProxies();

        setStatus("监控中");

        // 看门狗：立即执行一次，之后 15 秒循环
        handler.post(watchdogRunnable);

        // 播放/暂停劫持：复用本服务的常驻能力，静音键 -> 注入媒体播放/暂停键
        muteKeyRelay = new MuteKeyRelay(this, this::getForegroundPackage);
        muteKeyRelay.start();

        // 热点自动开启：
        // 1. 立即开始每 2 秒探测 WifiService 是否就绪（零开销）
        // 2. 15 秒兜底：即使探测一直没拿到配置，到点也强制 try 一次
        handler.post(hotspotProbeRunnable);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!hotspotAttemptStarted) {
                    FileLogger.i(TAG, "热点探测 15 秒兜底超时，强制触发首次尝试");
                    hotspotAttemptStarted = true;
                    handler.post(hotspotRunnable);
                }
            }
        }, HOTSPOT_FIRST_CHECK_DELAY_MS);

        FileLogger.i(TAG, "前台服务已启动，热点开关="
                + prefs.getBoolean(MainActivity.KEY_HOTSPOT_SWITCH, true));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(btStateReceiver);
        } catch (Exception e) {
            FileLogger.e(TAG, "注销广播接收器失败", e);
        }
        handler.removeCallbacksAndMessages(null);

        if (muteKeyRelay != null) {
            muteKeyRelay.stop();
            muteKeyRelay = null;
        }

        if (bluetoothAdapter != null) {
            closeProxy(BluetoothProfile.A2DP, a2dpProxy);
            closeProxy(PROFILE_A2DP_SINK, a2dpSinkProxy);
            closeProxy(BluetoothProfile.HEADSET, headsetProxy);
            closeProxy(PROFILE_HEADSET_CLIENT, headsetClientProxy);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void closeProxy(int profile, BluetoothProfile proxy) {
        if (proxy == null) return;
        try {
            bluetoothAdapter.closeProfileProxy(profile, proxy);
        } catch (Exception e) {
            FileLogger.e(TAG, "关闭 Profile 代理失败：" + profile, e);
        }
    }

    // ==================== 核心状态机 ====================

    /**
     * 状态机判定（每 15 秒执行一次）：
     * 1. 蓝牙已连接绑定手机          -> 静默监控
     * 2. 未连接，但 CarLife 在前台    -> 完全静默（手机走 WiFi 互联，故意断蓝牙）
     * 3. 未连接，绑定手机热点在线     -> 主动连接蓝牙（20 秒限频）
     * 4. 未连接，手机不在附近         -> 本次开机断连过才广播；否则仅等待
     */
    private void evaluateState() {
        // 蓝牙回连监控总开关：用户关了就停止所有蓝牙监控操作
        if (!prefs.getBoolean(MainActivity.KEY_BT_MONITOR_SWITCH, true)) {
            stopAllLoops();
            setStatus("蓝牙监控已关闭");
            return;
        }

        if (bluetoothAdapter == null) {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                setStatus("蓝牙服务未就绪，等待中");
                return;
            }
        }

        if (!bluetoothAdapter.isEnabled()) {
            setStatus("蓝牙已关闭，正在恢复");
            long now = SystemClock.elapsedRealtime();
            if (now - lastEnableAttemptTime >= ENABLE_BT_RETRY_INTERVAL_MS) {
                lastEnableAttemptTime = now;
                FileLogger.w(TAG, "看门狗检测到蓝牙已关闭，尝试重新开启");
                enableBluetooth();
            }
            return;
        }

        ensureProfileProxies();

        String boundMac = prefs.getString(MainActivity.KEY_MAC, null);
        if (boundMac == null) {
            stopAllLoops();
            setStatus("监控中（未绑定手机）");
            return;
        }

        Boolean connected = isBoundDeviceConnected(boundMac);
        if (connected == null) return; // 无法判断时保持原状态

        if (connected) {
            disconnectSeen = false;
            stopAllLoops();
            setStatus("监控中");
            return;
        }

        // 状态 2：CarLife 在前台 = 手机正通过 WiFi 互联，蓝牙是故意断的，绝不能去连
        String carlifePkg = prefs.getString(MainActivity.KEY_CARLIFE_PKG, null);
        if (carlifePkg != null && carlifePkg.equals(getForegroundPackage())) {
            stopAllLoops();
            setStatus("CarLife 前台运行中，蓝牙静默");
            return;
        }

        // 状态 3：手机连着车机热点但蓝牙没连 -> 主动连接
        String apMac = prefs.getString(MainActivity.KEY_AP_MAC, null);
        if (apMac != null && isApClientOnline(apMac)) {
            stopDiscoverableLoop();
            startConnectLoop();
            setStatus("检测到手机热点在线，正在主动连接蓝牙");
            return;
        }

        // 状态 4：手机不在附近
        stopConnectLoop();
        if (disconnectSeen) {
            startDiscoverableLoop();
            setStatus("等待手机回连，正在广播");
        } else {
            stopDiscoverableLoop();
            setStatus("监控中（等待手机）");
        }
    }

    private void stopAllLoops() {
        stopDiscoverableLoop();
        stopConnectLoop();
    }

    // ==================== Profile 代理补齐 ====================

    /** 每 30 秒补齐缺失的 Profile 代理（开机蓝牙服务可能启动很慢） */
    private void ensureProfileProxies() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastProfileRequestTime < PROFILE_RETRY_INTERVAL_MS) {
            return;
        }
        lastProfileRequestTime = now;

        if (a2dpProxy == null) {
            requestProfile(BluetoothProfile.A2DP, "A2DP(Source)");
        }
        if (a2dpSinkProxy == null) {
            requestProfile(PROFILE_A2DP_SINK, "A2DP_SINK");
        }
        if (headsetProxy == null) {
            requestProfile(BluetoothProfile.HEADSET, "HFP(AG)");
        }
        if (headsetClientProxy == null) {
            requestProfile(PROFILE_HEADSET_CLIENT, "HFP(HF)");
        }
    }

    private void requestProfile(int profile, String name) {
        try {
            boolean ok = bluetoothAdapter.getProfileProxy(this, profileListener, profile);
            FileLogger.i(TAG, "请求 " + name + " 代理返回：" + ok);
        } catch (Exception e) {
            FileLogger.e(TAG, "请求 " + name + " 代理异常", e);
        }
    }

    // ==================== 主动连接蓝牙（限频） ====================

    private void startConnectLoop() {
        if (connectLoopActive) return;
        connectLoopActive = true;
        handler.removeCallbacks(connectRunnable);
        handler.post(connectRunnable); // 立即尝试第一次
    }

    private void stopConnectLoop() {
        connectLoopActive = false;
        handler.removeCallbacks(connectRunnable);
    }

    /** 通过隐藏的 connect() 方法主动连接绑定手机，Sink/HF 方向优先 */
    private void attemptConnectBoundPhone() {
        String boundMac = prefs.getString(MainActivity.KEY_MAC, null);
        if (boundMac == null || bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            stopConnectLoop();
            return;
        }
        BluetoothDevice device;
        try {
            device = bluetoothAdapter.getRemoteDevice(boundMac);
        } catch (IllegalArgumentException e) {
            FileLogger.e(TAG, "绑定 MAC 非法：" + boundMac, e);
            stopConnectLoop();
            return;
        }

        FileLogger.i(TAG, "主动发起蓝牙连接（Sink/HF 优先）");
        boolean attempted = false;

        if (a2dpSinkProxy != null) {
            reflectProfileConnect(a2dpSinkProxy, device, "A2DP_SINK");
            attempted = true;
        }
        if (headsetClientProxy != null) {
            reflectProfileConnect(headsetClientProxy, device, "HFP_HF");
            attempted = true;
        }
        if (a2dpProxy != null) {
            reflectProfileConnect(a2dpProxy, device, "A2DP_SOURCE");
            attempted = true;
        }
        if (headsetProxy != null) {
            reflectProfileConnect(headsetProxy, device, "HFP_AG");
            attempted = true;
        }

        if (!attempted) {
            FileLogger.w(TAG, "所有蓝牙 Profile 代理均未就绪，本轮跳过并重新请求代理");
            lastProfileRequestTime = 0L;
            ensureProfileProxies();
        }
    }

    private void reflectProfileConnect(BluetoothProfile proxy, BluetoothDevice device, String name) {
        if (proxy == null) return;
        try {
            Method method = proxy.getClass().getMethod("connect", BluetoothDevice.class);
            Object result = method.invoke(proxy, device);
            FileLogger.i(TAG, name + ".connect() 调用结果：" + result);
        } catch (Exception e) {
            FileLogger.e(TAG, name + ".connect() 反射失败", e);
        }
    }

    // ==================== 条件探测 ====================

    /** 反射 BluetoothDevice.isConnected() 查询真实 ACL 链路；null 表示无法判断 */
    private Boolean isBoundDeviceConnected(String boundMac) {
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(boundMac);
            Method method = BluetoothDevice.class.getMethod("isConnected");
            return (Boolean) method.invoke(device);
        } catch (Exception e) {
            FileLogger.e(TAG, "isConnected 反射失败，本轮跳过判断", e);
            return null;
        }
    }

    /** 获取当前前台应用包名（需 REAL_GET_TASKS 权限 + 系统签名） */
    private String getForegroundPackage() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                ComponentName top = tasks.get(0).topActivity;
                if (top != null) return top.getPackageName();
            }
        } catch (Exception e) {
            FileLogger.e(TAG, "获取前台应用失败", e);
        }
        return null;
    }

    /** 检查绑定 MAC 的手机是否仍在车机热点上（解析 /proc/net/arp） */
    private boolean isApClientOnline(String boundApMac) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            reader.readLine(); // 跳过表头
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 4) continue;
                String flags = parts[2];
                String mac   = parts[3];
                if ("0x0".equals(flags)) continue;
                if (boundApMac.equalsIgnoreCase(mac)) return true;
            }
        } catch (Exception e) {
            FileLogger.e(TAG, "读取 ARP 表失败", e);
        }
        return false;
    }

    // ==================== 热点自检 ====================

    private boolean isHotspotEnabled() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            Method method = WifiManager.class.getMethod("getWifiApState");
            int state = (Integer) method.invoke(wm);
            return state == WIFI_AP_STATE_ENABLED || state == WIFI_AP_STATE_ENABLING;
        } catch (Exception e) {
            FileLogger.e(TAG, "getWifiApState 反射失败", e);
            return false;
        }
    }

    /**
     * 尝试从 WifiManager 反射调用 getWifiApConfiguration() 拿系统存的热点配置。
     * 车机第一次启动时 WifiService 还没从磁盘读配置，会返回 null；
     * 系统热点子系统就绪后就能拿到非 null 的 WifiConfiguration。
     */
    private WifiConfiguration tryGetWifiApConfig() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            Method method = findMethod(wm, "getWifiApConfiguration");
            if (method == null) return null;
            method.setAccessible(true);
            return (WifiConfiguration) method.invoke(wm);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 打开热点：
     * 隐藏 API 豁免已生效（WifiManager 110 方法全可见），startSoftAp / setWifiApEnabled
     * 均存在，但 ROM 对 null WifiConfiguration 容忍度极低，必须先拿到或构造一个有效配置。
     * startTethering 在本 ROM 上已证实会杀进程，作为最后兜底但默认跳过。
     */
    private void enableHotspotByReflection() {
        if (tryLegacyHotspotApi()) {
            return;
        }
        // 本 ROM 上 startTethering(Unsafe 回调) 曾导致进程崩溃，默认跳过该兜底
        FileLogger.w(TAG, "旧热点方法均不可用，热点自动开启暂时跳过（startTethering 在此 ROM 上会杀进程）");
    }

    /** 扫描并调用 ROM 里可能存在的旧热点隐藏方法 */
    private boolean tryLegacyHotspotApi() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return false;

            Method[] declared = WifiManager.class.getDeclaredMethods();
            FileLogger.i(TAG, "WifiManager 可见方法数：" + declared.length
                    + "（被隐藏 API 过滤时约 60，完全可见约 100+）");

            // 优先准备一份有效的 WifiConfiguration，所有热点方法都依赖它
            WifiConfiguration config = buildValidHotspotConfig(wm);
            FileLogger.i(TAG, "热点配置准备就绪：SSID=" + (config == null ? "null" : config.SSID));
            if (config == null) {
                FileLogger.e(TAG, "无法构造有效热点配置，放弃自动开启");
                return false;
            }

            Method setEnabledLike = null;
            Method startSoftAp = null;
            for (Method m : declared) {
                String name = m.getName();
                Class<?>[] types = m.getParameterTypes();
                if (("setWifiApEnabled".equals(name) || "setSoftApEnabled".equals(name))
                        && types.length == 2
                        && types[0] == WifiConfiguration.class
                        && types[1] == boolean.class) {
                    setEnabledLike = m;
                }
                if ("startSoftAp".equals(name)
                        && (types.length == 0
                        || (types.length == 1 && types[0] == WifiConfiguration.class))) {
                    startSoftAp = m;
                }
            }

            if (setEnabledLike != null) {
                FileLogger.i(TAG, "找到隐藏热点方法：" + setEnabledLike);
                setEnabledLike.setAccessible(true);
                boolean result = Boolean.TRUE.equals(setEnabledLike.invoke(wm, config, true));
                FileLogger.i(TAG, setEnabledLike.getName() + "(config, true) 结果：" + result);
                return result;
            }

            if (startSoftAp != null) {
                startSoftAp.setAccessible(true);
                if (startSoftAp.getParameterTypes().length == 0) {
                    // 0 参数版本内部会从系统存储读配置，但部分 ROM 实现有 bug，
                    // 保险起见先调 setWifiApConfiguration 写入再调 startSoftAp
                    Method setConfig = findMethod(wm, "setWifiApConfiguration", WifiConfiguration.class);
                    if (setConfig != null) {
                        setConfig.setAccessible(true);
                        setConfig.invoke(wm, config);
                        FileLogger.i(TAG, "已 setWifiApConfiguration，再调 startSoftAp()");
                    } else {
                        FileLogger.w(TAG, "找不到 setWifiApConfiguration，startSoftAp() 可能失败");
                    }
                    startSoftAp.invoke(wm);
                } else {
                    startSoftAp.invoke(wm, config);
                }
                FileLogger.i(TAG, "startSoftAp 已调用，由 60 秒复查确认结果");
                return true;
            }

            FileLogger.w(TAG, "本 ROM 没有任何可识别的热点方法");
            dumpHotspotMethods();
        } catch (Throwable e) {
            FileLogger.e(TAG, "旧热点 API 调用失败", e);
        }
        return false;
    }

    /** 读取系统已存的热点配置并确保能被 ROM 的 startSoftAp / setWifiApEnabled 安全读取 */
    private WifiConfiguration buildValidHotspotConfig(WifiManager wm) {
        WifiConfiguration cfg = tryGetWifiApConfig();
        if (cfg == null) {
            FileLogger.e(TAG, "系统未存储任何热点配置，请先在车机设置里配好热点");
            return null;
        }
        FileLogger.i(TAG, "使用系统已有热点配置：SSID=" + cfg.SSID);
        // 只补 apChannel=6（部分 ROM 的 startSoftAp 会直接读这个字段，为 0 时崩）
        try {
            Field channel = WifiConfiguration.class.getDeclaredField("apChannel");
            channel.setAccessible(true);
            if (channel.getInt(cfg) == 0) {
                channel.setInt(cfg, 6);
                FileLogger.i(TAG, "补 apChannel=6");
            }
        } catch (Throwable ignored) {
        }
        return cfg;
    }

    /** 在 WifiManager 反射方法列表中按名字和参数类型查找某个方法 */
    private Method findMethod(WifiManager wm, String name, Class<?>... paramTypes) {
        for (Method m : WifiManager.class.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] types = m.getParameterTypes();
            if (types.length != paramTypes.length) continue;
            boolean match = true;
            for (int i = 0; i < types.length; i++) {
                if (!types[i].equals(paramTypes[i])) {
                    match = false;
                    break;
                }
            }
            if (match) return m;
        }
        return null;
    }

    /**
     * Android 8.0+ 标准热点开启接口，全部走反射（公开 SDK 里不含这些符号）。
     * TETHERING_WIFI = 0。
     * 该路径在本车机上曾导致进程崩溃（疑似系统回调 Unsafe 分配的抽象类实例），
     * 因此：每一步单独打日志定位；先落盘"尝试中"标记，存活 10 秒后才清除；
     * 一旦崩溃过，永久跳过本路径，避免开机反复闪退。
     */
    private void startTetheringByApi() {
        if (prefs.getBoolean(KEY_TETHER_CRASHED, false)) {
            FileLogger.w(TAG, "startTethering 此前已导致进程崩溃，已永久跳过该路径");
            return;
        }
        FileLogger.i(TAG, "尝试 ConnectivityManager.startTethering(TETHERING_WIFI)");
        // 崩溃保护标记：必须同步落盘，apply() 可能在崩溃前来不及写
        prefs.edit().putBoolean(KEY_TETHER_CRASHED, true).commit();
        try {
            FileLogger.i(TAG, "步骤1/4：获取 ConnectivityManager");
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                FileLogger.e(TAG, "ConnectivityManager 不可用");
                prefs.edit().remove(KEY_TETHER_CRASHED).commit();
                return;
            }
            FileLogger.i(TAG, "步骤2/4：反射 startTethering 方法");
            Class<?> cmClass = Class.forName("android.net.ConnectivityManager");
            Class<?> callbackClass = Class.forName(
                    "android.net.ConnectivityManager$OnStartTetheringCallback");
            Method start = cmClass.getMethod("startTethering",
                    int.class, boolean.class, callbackClass, Handler.class);

            FileLogger.i(TAG, "步骤3/4：先用 null 回调调用（部分 ROM 不做非空检查）");
            try {
                start.invoke(cm, 0, false, null, handler);
                FileLogger.i(TAG, "startTethering(null 回调) 调用成功");
            } catch (Exception e) {
                FileLogger.w(TAG, "null 回调被拒绝：" + e.getCause()
                        + "，改用 Unsafe 分配回调实例");
                FileLogger.i(TAG, "步骤4/4：Unsafe 分配回调实例并调用");
                Object callback = allocateInstance(callbackClass);
                start.invoke(cm, 0, false, callback, handler);
                FileLogger.i(TAG, "startTethering(Unsafe 回调) 调用成功");
            }

            // 存活 10 秒后才敢确认这条路径在本 ROM 上是安全的
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    prefs.edit().remove(KEY_TETHER_CRASHED).commit();
                    FileLogger.i(TAG, "startTethering 存活确认，热点已开启=" + isHotspotEnabled());
                }
            }, 10 * 1000);
        } catch (Throwable t) {
            // 走到这里说明是正常异常而非进程崩溃，清除标记允许下次再试
            prefs.edit().remove(KEY_TETHER_CRASHED).commit();
            FileLogger.e(TAG, "startTethering 失败", t);
            dumpHotspotMethods();
        }
    }

    /** 用 Unsafe 绕过构造函数创建抽象类实例（回调方法均为空实现，仅用于满足 API 参数） */
    private static Object allocateInstance(Class<?> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        return allocate.invoke(unsafe, clazz);
    }

    private static boolean sCrashLoggerInstalled = false;

    /** 进程级崩溃落盘（幂等）：把未捕获异常写进日志文件后再按默认流程崩溃 */
    public static synchronized void installCrashLogger() {
        if (sCrashLoggerInstalled) {
            return;
        }
        sCrashLoggerInstalled = true;
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                FileLogger.e(TAG, "FATAL 进程崩溃，线程=" + t.getName(), e);
                if (previous != null) {
                    previous.uncaughtException(t, e);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }
        });
    }

    /** 解除 Android 9+ 的隐藏 API 反射限制（系统签名应用可安全使用） */
    private static void forceHiddenApiExemptions() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "getRuntime", (Object) null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "setHiddenApiExemptions", new Class[]{String[].class});
            Object runtime = getRuntime.invoke(null);
            setExemptions.invoke(runtime, (Object) new String[]{"L"});
            FileLogger.i(TAG, "已尝试解除隐藏 API 限制");
        } catch (Throwable t) {
            FileLogger.w(TAG, "解除隐藏 API 限制失败：" + t);
        }
    }

    /** 失败时把 WifiManager / ConnectivityManager 里热点相关的方法打进日志，便于下一轮适配 */
    private void dumpHotspotMethods() {
        try {
            for (Method m : WifiManager.class.getDeclaredMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                if (name.contains("ap") || name.contains("tether") || name.contains("softap")) {
                    FileLogger.i(TAG, "WifiManager 方法：" + m);
                }
            }
            Class<?> cmClass = Class.forName("android.net.ConnectivityManager");
            for (Method m : cmClass.getDeclaredMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                if (name.contains("tether")) {
                    FileLogger.i(TAG, "ConnectivityManager 方法：" + m);
                }
            }
        } catch (Throwable ignored) {
            // 仅用于排查，失败不处理
        }
    }

    // ==================== 蓝牙广播处理 ====================

    private final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            boolean btEnabled = prefs.getBoolean(MainActivity.KEY_BT_MONITOR_SWITCH, true);

            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                FileLogger.i(TAG, "蓝牙适配器状态变化：" + state);
                if (state == BluetoothAdapter.STATE_OFF) {
                    a2dpProxy = null;
                    a2dpSinkProxy = null;
                    headsetProxy = null;
                    headsetClientProxy = null;
                    lastProfileRequestTime = 0L;
                    if (btEnabled) setStatus("蓝牙已关闭，正在恢复");
                    handler.removeCallbacks(watchdogRunnable);
                    handler.postDelayed(watchdogRunnable, 5000);
                } else if (state == BluetoothAdapter.STATE_ON) {
                    lastProfileRequestTime = 0L;
                    if (btEnabled) ensureProfileProxies();
                    handler.removeCallbacks(watchdogRunnable);
                    handler.post(watchdogRunnable);
                }
                return;
            }

            if (!btEnabled) return; // 总开关关了，忽略所有 ACL 事件

            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) return;

            String boundMac = prefs.getString(MainActivity.KEY_MAC, null);
            if (boundMac == null) return;
            if (!boundMac.equalsIgnoreCase(device.getAddress())) return;

            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                FileLogger.i(TAG, "绑定手机蓝牙已断开");
                disconnectSeen = true;
                handler.removeCallbacks(watchdogRunnable);
                handler.post(watchdogRunnable);
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                FileLogger.i(TAG, "绑定手机蓝牙已连接");
                disconnectSeen = false;
                stopAllLoops();
                setStatus("监控中");
            }
        }
    };

    // ==================== 可发现广播循环 ====================

    private void startDiscoverableLoop() {
        if (discoverableLoopActive) return;
        discoverableLoopActive = true;
        handler.removeCallbacks(discoverableRunnable);
        setScanModeByReflection(SCAN_MODE_CONNECTABLE_DISCOVERABLE, 0);
        handler.postDelayed(discoverableRunnable, DISCOVERABLE_INTERVAL_MS);
    }

    private void stopDiscoverableLoop() {
        if (!discoverableLoopActive) return;
        discoverableLoopActive = false;
        handler.removeCallbacks(discoverableRunnable);
        setScanModeByReflection(SCAN_MODE_CONNECTABLE, 0);
    }

    private void setScanModeByReflection(int mode, int durationSec) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        try {
            Method method = BluetoothAdapter.class.getMethod("setScanMode", int.class, int.class);
            method.invoke(bluetoothAdapter, mode, durationSec);
            FileLogger.i(TAG, "setScanMode(" + mode + ", " + durationSec + ") 调用成功");
        } catch (Exception e) {
            try {
                Method method = BluetoothAdapter.class.getMethod("setScanMode", int.class);
                method.invoke(bluetoothAdapter, mode);
                FileLogger.i(TAG, "setScanMode(" + mode + ") 调用成功");
            } catch (Exception e2) {
                FileLogger.e(TAG, "setScanMode 反射失败", e2);
            }
        }
    }

    private void enableBluetooth() {
        if (bluetoothAdapter == null) return;
        try {
            if (bluetoothAdapter.enable()) {
                FileLogger.i(TAG, "BluetoothAdapter.enable() 调用成功");
                return;
            }
        } catch (SecurityException e) {
            FileLogger.e(TAG, "enable() 权限不足，尝试反射", e);
        }
        try {
            Method method = BluetoothAdapter.class.getMethod("setBluetoothEnable", boolean.class);
            method.invoke(bluetoothAdapter, true);
            FileLogger.i(TAG, "setBluetoothEnable(true) 反射调用成功");
        } catch (Exception e) {
            FileLogger.e(TAG, "setBluetoothEnable 反射失败", e);
        }
    }

    // ==================== 通知与状态 ====================

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "蓝牙监控", NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("蓝牙监控中")
                .setContentText("正在监控绑定手机的连接状态")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    /** 状态变化才写 SharedPreferences 和日志，避免每 15 秒重复落盘 */
    private void setStatus(String status) {
        String old = prefs.getString(MainActivity.KEY_STATUS, null);
        if (!status.equals(old)) {
            prefs.edit().putString(MainActivity.KEY_STATUS, status).apply();
            FileLogger.i(TAG, "状态：" + status);
        }
    }

    // ==================== 开机自启接收器 ====================

    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                    || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {

                // 每次车机开机：清空上次残留日志，再静默启动前台服务
                FileLogger.init(context.getApplicationContext(), true);
                FileLogger.i(TAG, "收到开机广播：" + action + "，启动前台服务");

                try {
                    context.startForegroundService(new Intent(context, ForegroundService.class));
                } catch (Exception e) {
                    FileLogger.e(TAG, "启动前台服务失败", e);
                }

                // 开机 toast 提示
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(context.getApplicationContext(),
                                "手机互联助手已启动",
                                android.widget.Toast.LENGTH_SHORT).show());
            }
        }
    }
}