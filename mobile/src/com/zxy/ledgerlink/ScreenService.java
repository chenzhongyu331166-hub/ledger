package com.zxy.ledgerlink;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 屏幕读取：抓取当前界面文字里含金额的支付成功画面，上报入账。
 * 密码输入框 (isPassword) 直接跳过，绝不读取密码内容。
 */
public class ScreenService extends AccessibilityService {

    private String lastText = "";
    private long lastSend = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSend < 1200) {
            return;
        }
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            return;
        }
        if (root == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        collect(root, sb, 0);
        String txt = sb.toString().trim();
        if (txt.length() < 4 || txt.equals(lastText)) {
            return;
        }
        if (!Parser.looksLikeMoney(txt)) {
            return;
        }
        lastText = txt;
        lastSend = now;
        CharSequence pkg = event.getPackageName();
        final String src = pkg == null ? "screen" : pkg.toString();
        final String clip = txt.length() > 600 ? txt.substring(0, 600) : txt;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Store.get(getFilesDir()).handleIncoming("screen", src, "屏幕识别", clip, false);
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void collect(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 14 || sb.length() > 1800) {
            return;
        }
        if (node.isPassword()) {
            return;
        }
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) {
            sb.append(t).append('\n');
        }
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.length() > 0) {
            sb.append(cd).append('\n');
        }
        int n = node.getChildCount();
        for (int i = 0; i < n && sb.length() <= 1800; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception ignored) {
            }
            collect(child, sb, depth + 1);
            if (child != null) {
                try {
                    child.recycle();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
    }
}
