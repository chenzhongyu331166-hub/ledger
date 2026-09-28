package com.zxy.ledgerlink;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * WebView 与 Java 之间的桥：网页里原来的 fetch('/api/...') 全部转到本机实现。
 */
public class Bridge {

    private final Context ctx;

    public Bridge(Context ctx) {
        this.ctx = ctx;
    }

    private String err(String m) {
        return "{\"error\":\"" + Store.jsonEscape(m) + "\"}";
    }

    private static double num(JSONObject p, String key, double def) {
        Object v = p.opt(key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble((String) v);
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    private static String str(JSONObject p, String key) {
        Object v = p.opt(key);
        return v == null || v == JSONObject.NULL ? null : String.valueOf(v);
    }

    @JavascriptInterface
    public String call(String path, String method, String body) {
        try {
            JSONObject p = new JSONObject(body == null || body.length() == 0 ? "{}" : body);
            return route(path == null ? "" : path, method == null ? "GET" : method, p);
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            return err(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private String route(String path, String method, JSONObject p) {
        Store s = Store.get(ctx.getFilesDir());

        if (path.startsWith("/api/state")) {
            String month = "";
            int qi = path.indexOf("month=");
            if (qi >= 0) {
                month = path.substring(qi + 6);
                int amp = month.indexOf('&');
                if (amp >= 0) {
                    month = month.substring(0, amp);
                }
            }
            return s.stateJson(month);
        }
        if (path.equals("/api/export")) {
            return s.exportJson();
        }
        if (path.equals("/api/lan")) {
            return "{\"ip\":\"手机本机\",\"port\":0}";
        }
        if (path.equals("/app.apk")) {
            return err("本机就是 App，无需下载");
        }

        // ---- 流水 ----
        if (path.equals("/api/transaction") && method.equals("POST")) {
            Store.Tx t = s.addTx(str(p, "type"), str(p, "account"), str(p, "to"),
                    str(p, "category"), num(p, "amount", -1), str(p, "date"), str(p, "note"));
            return "{\"ok\":true,\"transaction\":" + Store.txJson(t) + "}";
        }
        if (path.startsWith("/api/transaction/")) {
            String id = path.substring("/api/transaction/".length());
            if (method.equals("PUT")) {
                Store.Tx t = s.updateTx(id, str(p, "type"), str(p, "account"), str(p, "to"),
                        str(p, "category"), num(p, "amount", -1), str(p, "date"), str(p, "note"));
                return "{\"ok\":true,\"transaction\":" + Store.txJson(t) + "}";
            }
            if (method.equals("DELETE")) {
                s.deleteTx(id);
                return "{\"ok\":true}";
            }
        }

        // ---- 账户 ----
        if (path.equals("/api/account") && method.equals("POST")) {
            Store.Account a = s.addAccount(str(p, "name"), str(p, "tag"), str(p, "tail"),
                    num(p, "initial", 0), str(p, "color"));
            return "{\"ok\":true,\"account\":{\"id\":\"" + a.id + "\"}}";
        }
        if (path.startsWith("/api/account/")) {
            String id = path.substring("/api/account/".length());
            if (method.equals("PUT")) {
                Double init = p.has("initial") ? Double.valueOf(num(p, "initial", 0)) : null;
                s.updateAccount(id, str(p, "name"), str(p, "tag"), str(p, "tail"), init, str(p, "color"));
                return "{\"ok\":true}";
            }
            if (method.equals("DELETE")) {
                s.deleteAccount(id);
                return "{\"ok\":true}";
            }
        }

        // ---- 预算 ----
        if (path.equals("/api/budget") && method.equals("POST")) {
            s.setBudget(str(p, "category"), num(p, "limit", 0));
            return "{\"ok\":true}";
        }

        // ---- 自动入账 ----
        if (path.equals("/api/notify") && method.equals("POST")) {
            Store.Incoming inc = s.handleIncoming(str(p, "kind"), str(p, "pkg"),
                    str(p, "title"), str(p, "text"), false);
            return "{\"ok\":true,\"action\":\"" + inc.action + "\",\"reason\":\""
                    + Store.jsonEscape(inc.reason) + "\"}";
        }
        if (path.equals("/api/simulate") && method.equals("POST")) {
            Store.Incoming inc = s.handleIncoming("screen", "com.zxy.simulate", "测试",
                    "支付成功 0.01元 商户:链路测试", true);
            return "{\"ok\":true,\"action\":\"" + inc.action + "\"}";
        }

        // ---- 对账差额 ----
        if (path.equals("/api/adjust") && method.equals("POST")) {
            String action = str(p, "action");
            int n = s.resolveAdjust(str(p, "account"),
                    action == null || action.length() == 0 ? "record" : action);
            return "{\"ok\":true,\"count\":" + n + "}";
        }

        // ---- 手机开关 ----
        if (path.equals("/device/status")) {
            return "{\"notify\":" + notifyEnabled() + ",\"screen\":" + screenEnabled() + "}";
        }
        if (path.equals("/device/open") && method.equals("POST")) {
            String which = str(p, "which");
            Intent i;
            if ("screen".equals(which)) {
                i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            } else {
                i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (Exception e) {
                return err("无法打开系统设置");
            }
            return "{\"ok\":true}";
        }

        return err("not found: " + path);
    }

    private boolean notifyEnabled() {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(),
                    "enabled_notification_listeners");
            return flat != null && flat.contains(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean screenEnabled() {
        try {
            AccessibilityManager am = (AccessibilityManager)
                    ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            for (AccessibilityServiceInfo info
                    : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (info.getId() != null && info.getId().contains(ctx.getPackageName())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
