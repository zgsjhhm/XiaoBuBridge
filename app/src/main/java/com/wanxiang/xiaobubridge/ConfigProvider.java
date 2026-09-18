package com.wanxiang.xiaobubridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

import java.util.Map;



/**
 * v2.0 跨应用只读配置通道。
 *
 * <p>模块的 SharedPreferences 位于 com.wanxiang.xiaobubridge 的数据目录，
 * 小布（com.heytap.speechassist）进程无法直接读取；本 Provider 由模块导出，
 * 供 Hook 侧通过 ContentResolver 查询配置。</p>
 *
 * <p>访问路径：
 * <ul>
 *   <li>{@code content://com.wanxiang.xiaobubridge.config/config} —— 全部键值对</li>
 *   <li>{@code content://com.wanxiang.xiaobubridge.config/config/<key>} —— 单个键</li>
 * </ul>
 * 仅提供读能力，写操作一律抛异常；并对调用方做 UID → 包名白名单校验，
 * 避免任意第三方应用读取含 API Key 的配置。</p>
 */
public class ConfigProvider extends ContentProvider {

    public static final String COLUMN_KEY = "key";
    public static final String COLUMN_VALUE = "value";

    private static final String[] COLUMNS = new String[]{COLUMN_KEY, COLUMN_VALUE};

    /** 允许读取配置的包名：本模块自身 + 小布助手 */
    private static final String[] ALLOWED_PACKAGES = new String[]{
            "com.wanxiang.xiaobubridge",
            "com.heytap.speechassist"
    };

    private SharedPreferences prefs;

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx == null) return false;
        Context app = ctx.getApplicationContext();
        prefs = app.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE);

        // v3.11 悬浮球自愈：本 Provider 是小布进程「按需拉起模块进程」的入口。
        //
        // ColorOS 从最近任务划掉模块卡片 = 对整包 force-stop（am_kill ... o-stop(40)），
        // 杀进程的同时停掉 OverlayBallService、撤销前台通知，并清空该包的全部闹钟 —
        // 所以 OverlayBallRestartReceiver 的「闹钟后复活」在 ColorOS 上必然无效。
        //
        // 但模块进程会被小布进程重新拉起：GatewayWatchdog 每秒查询一次配置，
        // 该查询经本 Provider 落到模块进程，系统便以 content provider 为由把它启动
        // （实测划掉后 0.7 秒）。进程刚起来时悬浮球服务必然不在跑，正是补回的时机。
        // 幂等且带退避，正常运行时只读一个 volatile 即返回。
        try {
            OverlayBallService.requestSelfHealAsync(app);
        } catch (Throwable t) {
            BridgeLog.i("[XiaoBuBridge] ConfigProvider: overlay self-heal failed: " + t);
        }
        return true;
    }

    /** 调用方白名单校验：同进程或命中允许包名才放行 */
    private boolean isCallerAllowed() {
        try {
            int uid = Binder.getCallingUid();
            if (uid == Process.myUid()) return true;

            Context ctx = getContext();
            if (ctx == null) return false;

            String[] packages = ctx.getPackageManager().getPackagesForUid(uid);
            if (packages == null) return false;

            for (String pkg : packages) {
                for (String allowed : ALLOWED_PACKAGES) {
                    if (allowed.equals(pkg)) return true;
                }
            }
        } catch (Throwable ignored) {
            // 校验异常一律拒绝
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        if (prefs == null || !isCallerAllowed()) return cursor;

        // v3.11 自愈重试：小布进程的 GatewayWatchdog 每秒查询一次配置，这里顺带
        // 兜一次悬浮球。首次自愈若被系统拒绝（后台启动 FGS 受节流），下一轮查询
        // 会按退避间隔再试，直到服务起来或用户关掉开关。
        // 服务已在跑时只读一个 volatile 即返回，正常路径零开销。
        try {
            OverlayBallService.requestSelfHealAsync(getContext());
        } catch (Throwable t) {
            BridgeLog.i("[XiaoBuBridge] ConfigProvider: overlay self-heal failed: " + t);
        }

        String last = uri.getLastPathSegment();
        Map<String, ?> all = prefs.getAll();

        // .../config：返回全部；.../config/<key>：只返回指定键。
        //
        // 关键修复：指定了具体键但该键在 prefs 里不存在时，必须返回 <b>空游标</b>，
        // 不能退回「返回全部」。旧实现在这种情形下把整张 prefs 表塞给调用方，
        // 而 ConfigManager.getStringInTarget() 只读第一行，于是拿到的是
        // getAll() 里任意一项的值 —— Hook 侧读到的根本不是自己请求的键。
        // 布尔键上表现为「明明是默认开启，却恒定解析成 false」：
        // v3.6 的 chunked_stream_enabled / auto_wake_enabled / keep_alive_enabled
        // 从未被 UI 写过，因此三个新功能全部静默失效（UI 显示开启、Hook 侧关闭）。
        if (last == null || "config".equals(last)) {
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                cursor.addRow(new Object[]{entry.getKey(), String.valueOf(entry.getValue())});
            }
        } else if (all.containsKey(last)) {
            cursor.addRow(new Object[]{last, String.valueOf(all.get(last))});
        }
        // else：键不存在 → 空游标，调用方据此回退到自己的默认值
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd." + ConfigManager.AUTHORITY + ".config";
    }

    /**
     * 写入配置：URI 最后一段为 key，ContentValues 的 'value' 列为值。
     *
     * <p><b>类型保真</b>：默认把值按 boolean / int / long / String 推断后<b>按原类型</b>
     * 写入 prefs。这一点必须做到，因为读回路径 {@code ConfigManager.getStringInTarget()}
     * 走的是 {@code String.valueOf(cursor.getString(...))}，而 Provider 的 query 也是
     * {@code String.valueOf(value)}；只要写侧存的是原生类型，两侧都能正确还原。
     * 若这里统一存成 String，{@code prefs.getAll()} 会返回 String，UI 侧
     * {@code getBoolean(key, default)} / {@code getInt(key, default)} 读取时
     * 会抛 ClassCastException 并回退默认值 —— 表现为「面板上改了、重启后变回去」。</p>
     */
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (prefs == null || !isCallerAllowed()) return null;

        String key = uri.getLastPathSegment();
        if (key == null || "config".equals(key)) return null;

        String value = values.getAsString("value");
        if (value == null) return null;

        writeTyped(key, value);
        BridgeLog.i("[XiaoBuBridge] ConfigProvider: wrote " + key + "=" + value);
        return uri.buildUpon().appendPath(key).build();
    }

    /** 按值的内容推断类型后写入，保证 UI 侧按同名类型读回 */
    private void writeTyped(String key, String value) {
        SharedPreferences.Editor editor = prefs.edit();
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            editor.putBoolean(key, Boolean.parseBoolean(trimmed));
        } else {
            Integer asInt = tryParseInt(trimmed);
            if (asInt != null) {
                editor.putInt(key, asInt);
            } else {
                editor.putString(key, value);
            }
        }
        editor.apply();
    }

    private static Integer tryParseInt(String s) {
        try {
            return Integer.valueOf(Integer.parseInt(s));
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (prefs == null || !isCallerAllowed()) return 0;

        String key = uri.getLastPathSegment();
        if (key == null || "config".equals(key)) return 0;

        String value = values.getAsString("value");
        if (value == null) return 0;

        writeTyped(key, value);
        return 1;
    }
}
