package com.zxy.ledgerlink;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * 读取系统通知：银行短信、微信支付、支付宝等到账/扣款提醒。
 * 只上报含金额关键词的通知，密码/验证码类在服务端丢弃。
 */
public class CaptureService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return;
        }
        String pkg = sbn.getPackageName();
        if (pkg != null && pkg.equals(getPackageName())) {
            return;
        }
        Notification n = sbn.getNotification();
        Bundle b = n.extras;
        CharSequence t = b == null ? null : b.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence x = b == null ? null : b.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence sub = b == null ? null : b.getCharSequence(Notification.EXTRA_SUB_TEXT);
        String title = t == null ? "" : t.toString();
        String text = x == null ? "" : x.toString();
        if (sub != null) {
            text = text + " " + sub;
        }
        String all = title + " " + text;
        if (!Parser.looksLikeMoney(all)) {
            return;
        }
        final String fKind = "notify";
        final String fPkg = pkg == null ? "" : pkg;
        final String fTitle = title;
        final String fText = text;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Store.get(getFilesDir()).handleIncoming(fKind, fPkg, fTitle, fText, false);
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    @Override
    public void onListenerDisconnected() {
    }
}
