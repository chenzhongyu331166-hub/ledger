package com.zxy.ledgerlink;

import java.io.File;
import java.io.FileWriter;
import java.util.Locale;

public class LedgerTest {

    static int pass = 0, fail = 0;

    static void ok(boolean cond, String msg) {
        if (cond) {
            pass++;
            System.out.println("PASS " + msg);
        } else {
            fail++;
            System.out.println("FAIL " + msg);
        }
    }

    static void eq(Object want, Object got, String msg) {
        boolean good = want == null ? got == null : want.equals(got);
        ok(good, msg + (good ? "" : "  want=" + want + " got=" + got));
    }

    static void eqd(double want, double got, String msg) {
        boolean good = Math.abs(want - got) < 0.005;
        ok(good, msg + (good ? "" : "  want=" + want + " got=" + got));
    }

    static String canon(Parser.Parsed p) {
        if (p == null) {
            return null;
        }
        return p.type + "|" + String.format(Locale.US, "%.2f", p.amount) + "|" + p.merchant;
    }

    static void parserTests() {
        String[][] cases = {
                {"您账户6225于9月28日支出人民币35.50元，商户：美团，余额3000.00元", "expense|35.50|美团"},
                {"微信支付：你于9月28日向「星巴克」付款33.00元", "expense|33.00|星巴克"},
                {"您尾号1234的龙卡信用卡9月28日发生一笔支出交易88.00元，商户京东商城", "expense|88.00|京东商城"},
                {"您账户6225于9月28日收入人民币8000.00元，代发工资", "income|8000.00|"},
                {"微信支付到账通知：您收到转账20.00元", "income|20.00|"},
                {"您尾号8888账户9月28日支出12.50元，商户全家便利店", "expense|12.50|全家便利店"},
                {"【银行】验证码123456，5分钟内有效", null},
                {"零钱充值50.00元", null},
                {"您账户余额为1234.56元", null},
                {"【美团】支付成功，实付23.80元，已优惠2.00元", "expense|23.80|"},
                {"购票成功，G1234次列车，票价553.00元", "expense|553.00|"},
        };
        for (String[] c : cases) {
            eq(c[1], canon(Parser.parse(c[0])), "parse: " + c[0]);
        }

        eq("cmb", Parser.bankOf("招商银行您账户..."), "bankOf cmb");
        eq("ccb", Parser.bankOf("建设银行龙卡"), "bankOf ccb");
        eq(null, Parser.bankOf("微信支付"), "bankOf none");
        eqd(3000.0, Parser.balanceOf("支出35.50元，余额3,000.00元"), "balanceOf with comma");
        eqd(0.0, Parser.balanceOf("支出35.50元"), "balanceOf none");
        ok(Parser.looksLikeMoney("支付成功 12.00元"), "looksLikeMoney 元");
        ok(Parser.looksLikeMoney("余额50.00元"), "looksLikeMoney 余额");
        ok(!Parser.looksLikeMoney("明天天气不错"), "looksLikeMoney negative");
        eq("餐饮", Parser.guessCategory("美团外卖", "美团"), "cat 美团");
        eq("购物", Parser.guessCategory("", "京东商城"), "cat 京东");
        eq("居住", Parser.guessCategory("电费", ""), "cat 电费");
        eq("工资", Parser.guessIncomeCat("代发工资"), "inccat 工资");
    }

    static boolean throwsIAE(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    static void storeTests(File tmp) throws Exception {
        Store s = new Store(tmp);
        eq(3, s.accounts().size(), "default 3 accounts");
        s.updateAccount("cmb", null, null, "6225", null, null);
        s.updateAccount("ccb", null, null, "1234", null, null);
        s.updateAccount("boc", null, null, "8888", null, null);

        Store.Tx t1 = s.addTx("expense", "cmb", "", "餐饮", 35.5, "2026-09-12", "lunch");
        s.addTx("income", "cmb", "", "工资", 8000, "2026-09-10", "pay");
        s.addTx("transfer", "cmb", "boc", "", 2000, "2026-09-11", "");
        eqd(5964.5, s.balanceOf("cmb"), "cmb = 8000-2000-35.5");
        eqd(2000.0, s.balanceOf("boc"), "boc = 2000 transfer only");
        eqd(0, s.balanceOf("ccb"), "ccb = 0");

        ok(throwsIAE(new Runnable() {
            public void run() {
                s.addTx("expense", "cmb", "", "餐饮", -1, "2026-09-12", "");
            }
        }), "reject negative");
        ok(throwsIAE(new Runnable() {
            public void run() {
                s.addTx("transfer", "boc", "boc", "", 10, "2026-09-12", "");
            }
        }), "reject self transfer");
        ok(throwsIAE(new Runnable() {
            public void run() {
                s.addTx("expense", "boc", "", "", 10, "2026-09-12", "");
            }
        }), "reject no category");
        ok(throwsIAE(new Runnable() {
            public void run() {
                s.addTx("expense", "boc", "", "餐饮", 10, "2026-9-12", "");
            }
        }), "reject bad date");

        s.updateTx(t1.id, "expense", "cmb", "", "餐饮", 50, "2026-09-13", "edit");
        eqd(5950.0, s.balanceOf("cmb"), "cmb after edit");
        s.deleteTx(t1.id);
        eqd(6000.0, s.balanceOf("cmb"), "cmb after delete");

        Store.Account a = s.addAccount("测试银行", "储蓄卡", "9999-8888", 100, "#123456");
        eq("998888", a.tail, "tail cleaned to last 6");
        eqd(100, s.balanceOf(a.id), "new account balance");
        final Store fs = s;
        ok(throwsIAE(new Runnable() {
            public void run() {
                fs.deleteAccount("cmb");
            }
        }), "refuse delete used account");
        s.deleteAccount(a.id);
        eq(3, s.accounts().size(), "unused account deleted");

        s.setBudget("餐饮", 1000);
        String sum = s.summaryJson("2026-09");
        ok(sum.contains("\"income\":8000.00"), "summary income");
        ok(sum.contains("\"budget_total\":1000.00"), "summary budget total");
        ok(sum.contains("\"category\":\"餐饮\""), "summary has 餐饮 cat");
        s.setBudget("餐饮", 0);
        ok(!s.summaryJson("2026-09").contains("\"budget_total\":1000.00"), "budget cleared");

        Store.Incoming inc = s.handleIncoming("notify", "com.chinapay.cmb", "交易提醒",
                "您账户6225于9月28日支出人民币35.50元，商户：美团，余额5000.00元", false);
        eq("inserted", inc.action, "incoming insert");
        eq("cmb", inc.tx.account, "incoming account via tail");
        eq("餐饮", inc.tx.category, "incoming cat");
        eqd(5000.0, s.balanceOf("cmb"), "balance snapshot applied = 5000");

        inc = s.handleIncoming("notify", "com.chinapay.cmb", "交易提醒",
                "您账户6225于9月28日支出人民币35.50元，商户：美团，余额5000.00元", false);
        eq("duplicate", inc.action, "incoming dedup 5min");

        inc = s.handleIncoming("notify", "x", "", "验证码 123456", false);
        eq("skipped", inc.action, "incoming skip code");

        inc = s.handleIncoming("notify", "x", "", "您账户6225于9月28日收入人民币8000.00元，代发工资", true);
        eq("inserted", inc.action, "incoming salary");
        eq("income", inc.tx.type, "salary type");
        eq("工资", inc.tx.category, "salary category");
        eq("cmb", inc.tx.account, "salary account via tail");

        inc = s.handleIncoming("notify", "com.chinapay.cmb", "交易提醒",
                "招商银行 您账户6225支出100.00元 商户沃尔玛 余额4900.00元", false);
        eq("inserted", inc.action, "insert wal-mart");
        eqd(4900.0, s.balanceOf("cmb"), "balance re-calibrated = 4900");
        eq(1, s.pendingAdjustCount("cmb"), "unexplained diff pending after 2nd calibration");
        eqd(-8000.0, s.pendingAdjustSum("cmb"), "pending sum = -8000 (inconsistent salary)");

        String st = s.stateJson("2026-09");
        FileWriter fw = new FileWriter(new File(tmp, "state_out.json"));
        fw.write(st);
        fw.close();
        ok(st.startsWith("{") && st.endsWith("}"), "stateJson braces");
        ok(st.contains("\"expense_categories\""), "stateJson has categories");
        ok(st.contains("\"by_account\""), "stateJson has by_account");
        ok(st.contains("\"transactions\":["), "stateJson has transactions");

        Store s2 = new Store(tmp);
        eq(s.accounts().size(), s2.accounts().size(), "reload accounts count");
        eq(s.transactions().size(), s2.transactions().size(), "reload tx count");
        eqd(s.balanceOf("cmb"), s2.balanceOf("cmb"), "reload balance");
    }

    /** 差额提醒：期初手填 → 首次校准静默修正 → 漏记的免密支付被红字差额抓住 → 补记后余额不动。 */
    static void adjustTests(File tmp) {
        Store s = new Store(tmp);
        s.updateAccount("cmb", null, null, "6225", null, null);
        s.updateAccount("cmb", null, null, null, 10000.0, null);
        eqd(10000.0, s.balanceOf("cmb"), "adj: manual initial 10000");

        // 首次带余额短信（余额-only，解析不出流水）→ 静默把期初修正到银行值
        Store.Incoming inc = s.handleIncoming("notify", "x", "",
                "招商银行 您账户6225余额7964.50元", false);
        eq("skipped", inc.action, "adj: balance-only skipped as txn");
        eqd(7964.5, s.balanceOf("cmb"), "adj: baseline corrected to 7964.5");
        eq(0, s.pendingAdjustCount("cmb"), "adj: first calibration silent");

        // 漏记一笔免密支付：只有余额变动短信、没有可解析流水 → 差额被记下
        inc = s.handleIncoming("notify", "x", "",
                "招商银行 您账户6225余额7940.50元", false);
        eq("skipped", inc.action, "adj: 2nd balance-only skipped");
        eqd(7940.5, s.balanceOf("cmb"), "adj: balance follows bank");
        eq(1, s.pendingAdjustCount("cmb"), "adj: diff pending raised");
        eqd(-24.0, s.pendingAdjustSum("cmb"), "adj: diff = -24");

        // 重启后差额仍在
        Store s2 = new Store(tmp);
        eq(1, s2.pendingAdjustCount("cmb"), "adj: pending survives reload");
        String st = s2.stateJson("2026-09");
        ok(st.contains("\"adjCount\":1") && st.contains("\"adj\":-24.00"), "adj in stateJson");

        // 一键补记：生成流水，且余额保持与银行一致
        int n = s2.resolveAdjust("cmb", "record");
        eq(1, n, "adj: record 1 txn");
        eqd(7940.5, s2.balanceOf("cmb"), "adj: balance unchanged after record");
        eq(0, s2.pendingAdjustCount("cmb"), "adj: pending cleared");
        Store.Tx fixed = null;
        for (Store.Tx t : s2.transactions()) {
            if (t.note.startsWith("[差额补记]")) {
                fixed = t;
            }
        }
        ok(fixed != null, "adj: diff txn created");
        if (fixed != null) {
            eq("expense", fixed.type, "adj: negative diff -> expense");
            eqd(24.0, fixed.amount, "adj: amount 24");
            eq("其他", fixed.category, "adj: category");
        }

        // 忽略路径
        s2.handleIncoming("notify", "x", "", "招商银行 您账户6225余额7900.00元", false);
        eq(1, s2.pendingAdjustCount("cmb"), "adj: new diff raised");
        n = s2.resolveAdjust("cmb", "ignore");
        eq(0, n, "adj: ignore returns 0");
        eq(0, s2.pendingAdjustCount("cmb"), "adj: ignore clears");
        eqd(7900.0, s2.balanceOf("cmb"), "adj: balance kept after ignore");

        // 无差额时补记要报错
        boolean threw = throwsIAE(new Runnable() {
            public void run() {
                s2.resolveAdjust("cmb", "record");
            }
        });
        ok(threw, "adj: record without pending throws");

        // 正常流水（金额与余额自洽）不产生差额
        s2.handleIncoming("notify", "x", "",
                "招商银行 您账户6225支出10.00元 商户滴滴 余额7890.00元", false);
        eqd(7890.0, s2.balanceOf("cmb"), "adj: normal txn balance");
        eq(0, s2.pendingAdjustCount("cmb"), "adj: self-consistent sms raises no diff");
    }

    public static void main(String[] args) throws Exception {
        parserTests();
        File tmp = new File(System.getProperty("java.io.tmpdir"),
                "ledger_store_test_" + System.nanoTime());
        tmp.mkdirs();
        storeTests(tmp);
        File tmp2 = new File(System.getProperty("java.io.tmpdir"),
                "ledger_adjust_test_" + System.nanoTime());
        tmp2.mkdirs();
        adjustTests(tmp2);
        FileWriter fw = new FileWriter(new File(tmp, "state_out2.json"));
        fw.write(new Store(tmp).stateJson("2026-09"));
        fw.close();
        System.out.println("STATE_FILE=" + new File(tmp, "state_out.json").getPath());
        System.out.println("----");
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }
}
