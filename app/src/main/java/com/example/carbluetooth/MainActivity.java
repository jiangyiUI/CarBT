package com.example.carbluetooth;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.ViewGroup;
import android.widget.Toast;
import android.view.WindowManager;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    // SharedPreferences 常量（与 ForegroundService 共用）
    public static final String PREF_NAME  = "bt_prefs";
    public static final String KEY_MAC    = "bound_mac";
    public static final String KEY_NAME   = "bound_name";
    public static final String KEY_STATUS = "service_status";
    public static final String KEY_HOTSPOT_SWITCH = "hotspot_auto_enable";
    /** 蓝牙回连监控总开关（关了就停止所有蓝牙相关操作） */
    public static final String KEY_BT_MONITOR_SWITCH = "bt_monitor_enable";
    /** 日志打印开关（默认关闭） */
    public static final String KEY_LOG_SWITCH = "log_print_enable";
    /** 播放/暂停劫持开关（静音键 -> 注入媒体播放/暂停键，默认开启） */
    public static final String KEY_HIJACK_SWITCH = "mute_hijack_enable";
    // 互联应用（CarLife）绑定信息
    public static final String KEY_CARLIFE_PKG   = "carlife_pkg";
    public static final String KEY_CARLIFE_LABEL = "carlife_label";
    // 热点在线设备（手机连车机热点后的 MAC）绑定信息
    public static final String KEY_AP_MAC  = "ap_client_mac";
    public static final String KEY_AP_INFO = "ap_client_info";

    private TextView tvBoundDevice;
    private TextView tvStatus;
    private TextView tvBoundCarlife;
    private TextView tvBoundApClient;
    private SharedPreferences prefs;
    private BluetoothAdapter bluetoothAdapter;

    // 每秒刷新一次服务状态显示
    private final Handler handler = new Handler();
    private final Runnable statusRunnable = new Runnable() {
        @Override
        public void run() {
            tvStatus.setText(prefs.getString(KEY_STATUS, "监控中"));
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 只隐藏状态栏，保留导航栏（必须在 setContentView 之前）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        // 让窗口无视系统状态栏和导航栏的尺寸限制，直接铺满全屏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        // 初始化落盘日志（界面调用不清理旧日志）
        FileLogger.init(this);
        ForegroundService.installCrashLogger();
        FileLogger.i(TAG, "主界面打开");

        tvBoundDevice   = findViewById(R.id.tvBoundDevice);
        tvStatus        = findViewById(R.id.tvStatus);
        tvBoundCarlife  = findViewById(R.id.tvBoundCarlife);
        tvBoundApClient = findViewById(R.id.tvBoundApClient);
        TextView tvSubtitle = findViewById(R.id.tvSubtitle);
        LinearLayout btnBind         = findViewById(R.id.btnBind);
        LinearLayout btnBindCarlife  = findViewById(R.id.btnBindCarlife);
        LinearLayout btnBindApClient = findViewById(R.id.btnBindApClient);
        ImageView ivSwitchBtMonitor = findViewById(R.id.ivSwitchBtMonitor);
        ImageView ivSwitchHotspot   = findViewById(R.id.ivSwitchHotspot);
        ImageView ivSwitchLog       = findViewById(R.id.ivSwitchLog);
        ImageView ivSwitchHijack    = findViewById(R.id.ivSwitchHijack);

        // 副标题：versionName 从 PackageManager 读取，避免硬编码
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            tvSubtitle.setText("蓝牙回连·开机热点（仅适用于安波福）v" + versionName);
        } catch (Exception e) {
            tvSubtitle.setText("蓝牙回连·开机热点（仅适用于安波福）");
        }

        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        refreshBoundDevice();
        refreshCarlife();
        refreshApClient();

        // 进入界面：尝试启动前台服务（手机预览可能起不来但不影响 UI）
        try {
            startForegroundService(new Intent(this, ForegroundService.class));
        } catch (Throwable ignored) { /* 普通签名手机上 ForegroundService 可能无法启动，静默忽略 */ }

        // ===== 热点自动开关（纯切图切换）=====
        updateSwitchImg(ivSwitchHotspot, prefs.getBoolean(KEY_HOTSPOT_SWITCH, true));
        ivSwitchHotspot.setOnClickListener(v -> {
            boolean isOn = !prefs.getBoolean(KEY_HOTSPOT_SWITCH, true);
            prefs.edit().putBoolean(KEY_HOTSPOT_SWITCH, isOn).apply();
            updateSwitchImg(ivSwitchHotspot, isOn);
            FileLogger.i(TAG, "热点自动打开开关：" + isOn);
            Toast.makeText(this, isOn ? "已开启：下次开机将自动打开热点" : "已关闭：开机不再自动打开热点", Toast.LENGTH_SHORT).show();
        });

        // ===== 蓝牙回连监控总开关（纯切图切换）=====
        updateSwitchImg(ivSwitchBtMonitor, prefs.getBoolean(KEY_BT_MONITOR_SWITCH, true));
        ivSwitchBtMonitor.setOnClickListener(v -> {
            boolean isOn = !prefs.getBoolean(KEY_BT_MONITOR_SWITCH, true);
            prefs.edit().putBoolean(KEY_BT_MONITOR_SWITCH, isOn).apply();
            updateSwitchImg(ivSwitchBtMonitor, isOn);
            FileLogger.i(TAG, "蓝牙回连监控总开关：" + isOn);
            Toast.makeText(this, isOn ? "已开启：将继续监控蓝牙回连" : "已关闭：停止所有蓝牙监控操作", Toast.LENGTH_SHORT).show();
        });

        // ===== 日志打印开关（默认关闭）=====
        boolean logOn = prefs.getBoolean(KEY_LOG_SWITCH, false);
        FileLogger.setEnabled(logOn);
        updateSwitchImg(ivSwitchLog, logOn);
        ivSwitchLog.setOnClickListener(v -> {
            boolean isOn = !prefs.getBoolean(KEY_LOG_SWITCH, false);
            prefs.edit().putBoolean(KEY_LOG_SWITCH, isOn).apply();
            FileLogger.setEnabled(isOn);
            updateSwitchImg(ivSwitchLog, isOn);
            if (isOn) {
                FileLogger.i(TAG, "日志打印已开启");
            }
            Toast.makeText(this, isOn ? "已开启：开始写入日志文件" : "已关闭：停止写入日志文件", Toast.LENGTH_SHORT).show();
        });

        // ===== 播放/暂停劫持开关（默认开启）=====
        updateSwitchImg(ivSwitchHijack, prefs.getBoolean(KEY_HIJACK_SWITCH, true));
        ivSwitchHijack.setOnClickListener(v -> {
            boolean isOn = !prefs.getBoolean(KEY_HIJACK_SWITCH, true);
            prefs.edit().putBoolean(KEY_HIJACK_SWITCH, isOn).apply();
            updateSwitchImg(ivSwitchHijack, isOn);
            FileLogger.i(TAG, "播放/暂停劫持开关：" + isOn);
            Toast.makeText(this, isOn
                    ? "已开启：静音键将暂停/播放互联应用"
                    : "已关闭：静音键恢复系统默认静音", Toast.LENGTH_SHORT).show();
        });

        btnBind.setOnClickListener(v -> showPairedDevicesDialog());
        btnBindCarlife.setOnClickListener(v -> showCarlifeAppDialog());
        btnBindApClient.setOnClickListener(v -> showApClientDialog());

        // ===== 使用帮助按钮 =====
        findViewById(R.id.btnHelp).setOnClickListener(v -> showHelpDialog());
    }

    /** 根据开关状态设置 ImageView 的切图（纯切图方案，无 Switch 控件） */
    private void updateSwitchImg(ImageView iv, boolean isOn) {
        iv.setImageResource(isOn ? R.drawable.switch_thumb_on : R.drawable.switch_thumb_off);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(statusRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusRunnable);
    }

    // ==================== 显示刷新 ====================

    private void refreshBoundDevice() {
        String mac  = prefs.getString(KEY_MAC, null);
        String name = prefs.getString(KEY_NAME, null);
        tvBoundDevice.setText(mac == null ? "未绑定" : name + "  " + mac);
    }

    private void refreshCarlife() {
        String pkg   = prefs.getString(KEY_CARLIFE_PKG, null);
        String label = prefs.getString(KEY_CARLIFE_LABEL, null);
        tvBoundCarlife.setText(pkg == null ? "未绑定" : label + "  " + pkg);
    }

    private void refreshApClient() {
        String info = prefs.getString(KEY_AP_INFO, null);
        tvBoundApClient.setText(info == null ? "未绑定" : info);
    }

    // ==================== 使用帮助弹窗 ====================

    /** 弹出使用说明；内容稍后替换为正式文案。窗口不超过 1280x720，点击外部可关闭。 */
    private void showHelpDialog() {
        ScrollView scrollView = new ScrollView(this);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        scrollView.setPadding(pad, pad, pad, pad);

        TextView content = new TextView(this);
        content.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24); // 强制 24px
        content.setLineSpacing(0, 1.25f);
        content.setText(readHelpText());
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("使用帮助")
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .setCancelable(true) // 点弹窗外部 / 返回键都可关闭
                .create();
        dialog.show();

        // 限制弹窗尺寸，确保不超过 1280x720
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = Math.min(1100, dm.widthPixels - pad * 4);
            int h = Math.min(640, dm.heightPixels - pad * 4);
            window.setLayout(w, h);
        }
    }

    /**
     * 从 assets/help.txt 读取使用帮助内容。
     * 该文件可直接用记事本编辑，换行即真实换行，无需写 \n。
     */
    private String readHelpText() {
        try {
            java.io.InputStream is = getAssets().open("help.txt");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            return new String(buffer, "UTF-8");
        } catch (Exception e) {
            return "（使用帮助内容读取失败，请检查 assets/help.txt 文件）";
        }
    }

    // ==================== 绑定手机蓝牙 ====================

    private void showPairedDevicesDialog() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "本机不支持蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "请先打开车机蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        if (bondedDevices == null || bondedDevices.isEmpty()) {
            Toast.makeText(this, "没有已配对的设备，请先在系统设置中配对手机", Toast.LENGTH_LONG).show();
            return;
        }
        final List<BluetoothDevice> deviceList = new ArrayList<>(bondedDevices);
        final CharSequence[] items = new CharSequence[deviceList.size()];
        for (int i = 0; i < deviceList.size(); i++) {
            BluetoothDevice d = deviceList.get(i);
            items[i] = d.getName() + "\n" + d.getAddress();
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要绑定的手机")
                .setItems(items, (dialog, which) -> {
                    BluetoothDevice selected = deviceList.get(which);
                    prefs.edit()
                            .putString(KEY_MAC, selected.getAddress())
                            .putString(KEY_NAME, selected.getName())
                            .apply();
                    FileLogger.i(TAG, "绑定手机：" + selected.getName() + " " + selected.getAddress());
                    refreshBoundDevice();
                    Toast.makeText(this, "已绑定：" + selected.getName(), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 绑定互联应用（CarLife） ====================

    /** 列出所有带桌面图标的应用，供用户手动绑定互联应用 */
    private void showCarlifeAppDialog() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(intent, 0);
        if (infos == null || infos.isEmpty()) {
            Toast.makeText(this, "未获取到应用列表", Toast.LENGTH_SHORT).show();
            return;
        }
        Collections.sort(infos, (a, b) -> {
            String la = a.loadLabel(pm).toString();
            String lb = b.loadLabel(pm).toString();
            return la.compareToIgnoreCase(lb);
        });

        final List<ResolveInfo> appList = infos;
        final CharSequence[] items = new CharSequence[appList.size()];
        for (int i = 0; i < appList.size(); i++) {
            ResolveInfo info = appList.get(i);
            items[i] = info.loadLabel(pm) + "\n" + info.activityInfo.packageName;
        }

        new AlertDialog.Builder(this)
                .setTitle("选择互联应用（如 CarLife）")
                .setItems(items, (dialog, which) -> {
                    ResolveInfo selected = appList.get(which);
                    prefs.edit()
                            .putString(KEY_CARLIFE_PKG, selected.activityInfo.packageName)
                            .putString(KEY_CARLIFE_LABEL, selected.loadLabel(pm).toString())
                            .apply();
                    FileLogger.i(TAG, "绑定互联应用：" + selected.activityInfo.packageName);
                    refreshCarlife();
                    Toast.makeText(this, "已绑定：" + selected.loadLabel(pm), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 绑定热点在线设备 ====================

    /** 读取当前连接到车机热点的设备列表（解析 /proc/net/arp） */
    private void showApClientDialog() {
        final List<String[]> clients = readApClients(); // 每项：[IP, MAC, 主机名]
        if (clients.isEmpty()) {
            Toast.makeText(this,
                    "当前没有设备连接车机热点，请先让手机连上车机热点，再点本按钮绑定",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final CharSequence[] items = new CharSequence[clients.size()];
        for (int i = 0; i < clients.size(); i++) {
            String[] c = clients.get(i);
            String hostname = c[2].isEmpty() ? "未知设备" : c[2];
            items[i] = hostname + "\nIP：" + c[0] + "  MAC：" + c[1];
        }
        new AlertDialog.Builder(this)
                .setTitle("选择热点在线的手机")
                .setItems(items, (dialog, which) -> {
                    String[] c = clients.get(which);
                    String hostname = c[2].isEmpty() ? "未知设备" : c[2];
                    String display = hostname + "  " + c[1];
                    prefs.edit()
                            .putString(KEY_AP_MAC, c[1])
                            .putString(KEY_AP_INFO, display)
                            .apply();
                    FileLogger.i(TAG, "绑定热点设备：" + display);
                    refreshApClient();
                    Toast.makeText(this, "已绑定热点设备：" + hostname, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 解析 ARP 表拿到热点客户端，并尝试从 DHCP 租约文件补充主机名 */
    private List<String[]> readApClients() {
        Map<String, String> hostnames = new HashMap<>();
        try (BufferedReader leaseReader = new BufferedReader(
                new FileReader("/data/misc/dhcp/dnsmasq.leases"))) {
            String line;
            while ((line = leaseReader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    hostnames.put(parts[1].toLowerCase(), parts[3]);
                }
            }
        } catch (Exception ignored) {
            // 无权限或文件不存在时，主机名显示"未知设备"
        }

        List<String[]> result = new ArrayList<>();
        try (BufferedReader arpReader = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            arpReader.readLine(); // 跳过表头
            while ((line = arpReader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 4) continue;
                String ip    = parts[0];
                String flags = parts[2];
                String mac   = parts[3];
                if ("0x0".equals(flags) || "00:00:00:00:00:00".equals(mac)) continue;
                String hostname = hostnames.containsKey(mac.toLowerCase())
                        ? hostnames.get(mac.toLowerCase()) : "";
                result.add(new String[]{ip, mac, hostname});
            }
        } catch (Exception e) {
            FileLogger.e(TAG, "读取热点设备列表失败", e);
            Toast.makeText(this, "读取热点设备列表失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        return result;
    }
}