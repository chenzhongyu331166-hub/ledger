package com.zxy.ledgerlink;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 手机本地账本存储（纯 Java，可在 JVM 单测）。
 * 数据文件在 app 私有目录：accounts.tsv / tx.tsv / budgets.tsv。
 */
public class Store {

    public static class Account {
        public String id, name, tag, tail, color;
        public double initial;
        public boolean calibrated;

        public Account(String id, String name, String tag, String tail, double initial, String color) {
            this.id = id;
            this.name = name;
            this.tag = tag;
            this.tail = tail;
            this.initial = initial;
            this.color = color;
        }
    }

    public static class Adj {
        public final double diff;
        public final String date;

        public Adj(double diff, String date) {
            this.diff = diff;
            this.date = date;
        }
    }

    public static class Tx {
        public String id, type, account, to, category, date, created, note;
        public double amount;

        public Tx(String id, String type, String account, String to, String category,
                  double amount, String date, String created, String note) {
            this.id = id;
            this.type = type;
            this.account = account;
            this.to = to;
            this.category = category;
            this.amount = amount;
            this.date = date;
            this.created = created;
            this.note = note;
        }
    }

    public static class Incoming {
        public final String action;   // inserted / duplicate / skipped
        public final String reason;
        public final Tx tx;

        public Incoming(String action, String reason, Tx tx) {
            this.action = action;
            this.reason = reason;
            this.tx = tx;
        }
    }

    public final String[] expenseCategories = {"餐饮", "交通", "购物", "日用", "娱乐",
            "居住", "学习", "医疗", "人情", "其他"};
    public final String[] incomeCategories = {"工资", "奖金", "兼职工资", "理财收益",
            "红包", "其他收入"};

    private static Store instance;

    public static synchronized Store get(File dir) {
        if (instance == null) {
            instance = new Store(dir);
        }
        return instance;
    }

    private final File dir;
    private final List<Account> accounts = new ArrayList<Account>();
    private final List<Tx> txs = new ArrayList<Tx>();
    private final Map<String, Double> budgets = new LinkedHashMap<String, Double>();
    private final Map<String, Long> dedup = new HashMap<String, Long>();
    private final Map<String, List<Adj>> adjusts = new LinkedHashMap<String, List<Adj>>();

    public Store(File dir) {
        this.dir = dir;
        load();
        if (accounts.isEmpty()) {
            accounts.add(new Account("cmb", "招商银行", "工资卡", "", 0, "#e11d48"));
            accounts.add(new Account("ccb", "建设银行", "工资卡", "", 0, "#1d4ed8"));
            accounts.add(new Account("boc", "中国银行", "主力消费", "", 0, "#b45309"));
            save();
        }
    }

    // ---------------- 基础读写 ----------------

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unesc(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 't') {
                    sb.append('\t');
                } else if (n == 'n') {
                    sb.append('\n');
                } else if (n == 'r') {
                    sb.append('\r');
                } else {
                    sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String[] cols(String line) {
        return line.split("\t", -1);
    }

    private String path(String name) {
        return new File(dir, name).getPath();
    }

    private List<String> readLines(String name) {
        List<String> out = new ArrayList<String>();
        File f = new File(dir, name);
        if (!f.exists()) {
            return out;
        }
        try {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() > 0) {
                    out.add(line);
                }
            }
            br.close();
        } catch (Exception ignored) {
        }
        return out;
    }

    private void writeLines(String name, List<String> lines) {
        try {
            dir.mkdirs();
            OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(path(name), false), StandardCharsets.UTF_8);
            for (String line : lines) {
                w.write(line);
                w.write("\n");
            }
            w.close();
        } catch (Exception ignored) {
        }
    }

    private void load() {
        for (String line : readLines("accounts.tsv")) {
            String[] c = cols(line);
            if (c.length >= 6) {
                try {
                    Account a = new Account(c[0], unesc(c[1]), unesc(c[2]), unesc(c[3]),
                            Double.parseDouble(c[4]), unesc(c[5]));
                    if (c.length >= 7) {
                        a.calibrated = "1".equals(c[6]);
                    }
                    accounts.add(a);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (String line : readLines("tx.tsv")) {
            String[] c = cols(line);
            if (c.length >= 9) {
                try {
                    txs.add(new Tx(c[0], c[1], c[2], c[3], c[4],
                            Double.parseDouble(c[5]), c[6], c[7], unesc(c[8])));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (String line : readLines("budgets.tsv")) {
            String[] c = cols(line);
            if (c.length >= 2) {
                try {
                    budgets.put(unesc(c[0]), Double.parseDouble(c[1]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (String line : readLines("adjust.tsv")) {
            String[] c = cols(line);
            if (c.length >= 3) {
                try {
                    String acc = c[0];
                    List<Adj> list = adjusts.get(acc);
                    if (list == null) {
                        list = new ArrayList<Adj>();
                        adjusts.put(acc, list);
                    }
                    list.add(new Adj(Double.parseDouble(c[1]), c[2]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private void save() {
        List<String> lines = new ArrayList<String>();
        for (Account a : accounts) {
            lines.add(a.id + "\t" + esc(a.name) + "\t" + esc(a.tag) + "\t" + esc(a.tail)
                    + "\t" + fmt(a.initial) + "\t" + esc(a.color) + "\t" + (a.calibrated ? "1" : "0"));
        }
        writeLines("accounts.tsv", lines);
        lines = new ArrayList<String>();
        for (Tx t : txs) {
            lines.add(t.id + "\t" + t.type + "\t" + t.account + "\t" + t.to + "\t"
                    + esc(t.category) + "\t" + fmt(t.amount) + "\t" + t.date + "\t"
                    + t.created + "\t" + esc(t.note));
        }
        writeLines("tx.tsv", lines);
        lines = new ArrayList<String>();
        for (Map.Entry<String, Double> e : budgets.entrySet()) {
            lines.add(esc(e.getKey()) + "\t" + fmt(e.getValue()));
        }
        writeLines("budgets.tsv", lines);
        lines = new ArrayList<String>();
        for (Map.Entry<String, List<Adj>> e : adjusts.entrySet()) {
            for (Adj adj : e.getValue()) {
                lines.add(e.getKey() + "\t" + fmt(adj.diff) + "\t" + adj.date);
            }
        }
        writeLines("adjust.tsv", lines);
    }

    public static String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private static String now(String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(new Date());
    }

    private static String today() {
        return now("yyyy-MM-dd");
    }

    public static String txJson(Tx t) {
        return "{\"id\":\"" + jsonEscape(t.id)
                + "\",\"type\":\"" + t.type
                + "\",\"account\":\"" + jsonEscape(t.account)
                + "\",\"to\":\"" + jsonEscape(t.to)
                + "\",\"category\":\"" + jsonEscape(t.category)
                + "\",\"amount\":" + fmt(t.amount)
                + ",\"date\":\"" + t.date
                + "\",\"note\":\"" + jsonEscape(t.note)
                + "\",\"created\":\"" + jsonEscape(t.created) + "\"}";
    }

    public static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------------- 查询 ----------------

    public synchronized List<Account> accounts() {
        return new ArrayList<Account>(accounts);
    }

    public synchronized List<Tx> transactions() {
        List<Tx> out = new ArrayList<Tx>(txs);
        return out;
    }

    private Account findAccount(String id) {
        for (Account a : accounts) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return null;
    }

    private double deltaOf(String accId) {
        double d = 0;
        for (Tx t : txs) {
            if (t.type.equals("income") && t.account.equals(accId)) {
                d += t.amount;
            } else if (t.type.equals("expense") && t.account.equals(accId)) {
                d -= t.amount;
            } else if (t.type.equals("transfer")) {
                if (t.account.equals(accId)) {
                    d -= t.amount;
                }
                if (t.to.equals(accId)) {
                    d += t.amount;
                }
            }
        }
        return Math.round(d * 100) / 100.0;
    }

    public synchronized double balanceOf(String accId) {
        Account a = findAccount(accId);
        if (a == null) {
            return 0;
        }
        return Math.round((a.initial + deltaOf(accId)) * 100) / 100.0;
    }

    public static String monthOf(Tx t) {
        return t.date.length() >= 7 ? t.date.substring(0, 7) : "";
    }

    // ---------------- 变更 ----------------

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new IllegalArgumentException(msg);
        }
    }

    public synchronized Tx addTx(String type, String account, String to, String category,
                                 double amount, String date, String note) {
        check(type.equals("expense") || type.equals("income") || type.equals("transfer"),
                "type 必须是 expense/income/transfer");
        check(amount > 0, "金额必须大于 0");
        check(findAccount(account) != null, "请选择账户");
        if (type.equals("transfer")) {
            check(findAccount(to) != null, "请选择转入账户");
            check(!to.equals(account), "转出和转入账户不能相同");
        }
        if (!type.equals("transfer")) {
            check(category != null && category.trim().length() > 0, "请选择分类");
        }
        check(date != null && date.matches("\\d{4}-\\d{2}-\\d{2}"), "日期格式应为 YYYY-MM-DD");
        String id = String.format(Locale.US, "%08x%04x",
                new Date().getTime() & 0xffffffffL, (int) (Math.random() * 0x10000));
        Tx t = new Tx(id, type, account, type.equals("transfer") ? to : "",
                category == null ? "" : category.trim(), Math.round(amount * 100) / 100.0,
                date, now("yyyy-MM-dd HH:mm:ss"), note == null ? "" : note.trim());
        if (t.note.length() > 120) {
            t.note = t.note.substring(0, 120);
        }
        txs.add(t);
        save();
        return t;
    }

    public synchronized Tx updateTx(String id, String type, String account, String to,
                                    String category, double amount, String date, String note) {
        int idx = -1;
        for (int i = 0; i < txs.size(); i++) {
            if (txs.get(i).id.equals(id)) {
                idx = i;
                break;
            }
        }
        check(idx >= 0, "记录不存在");
        String created = txs.get(idx).created;
        String type0 = txs.get(idx).type;
        if (type == null) {
            type = type0;
        }
        Tx t = new Tx(id, type, account, type.equals("transfer") ? (to == null ? "" : to) : "",
                category == null ? "" : category.trim(), Math.round(amount * 100) / 100.0,
                date, created, note == null ? "" : note.trim());
        if (t.note.length() > 120) {
            t.note = t.note.substring(0, 120);
        }
        check(t.type.equals("expense") || t.type.equals("income") || t.type.equals("transfer"),
                "type 必须是 expense/income/transfer");
        check(t.amount > 0, "金额必须大于 0");
        check(findAccount(t.account) != null, "请选择账户");
        if (t.type.equals("transfer")) {
            check(findAccount(t.to) != null, "请选择转入账户");
            check(!t.to.equals(t.account), "转出和转入账户不能相同");
        }
        check(t.date.matches("\\d{4}-\\d{2}-\\d{2}"), "日期格式应为 YYYY-MM-DD");
        txs.set(idx, t);
        save();
        return t;
    }

    public synchronized void deleteTx(String id) {
        int before = txs.size();
        for (int i = 0; i < txs.size(); i++) {
            if (txs.get(i).id.equals(id)) {
                txs.remove(i);
                save();
                return;
            }
        }
        check(before != txs.size(), "记录不存在");
        throw new IllegalArgumentException("记录不存在");
    }

    public synchronized Account addAccount(String name, String tag, String tail,
                                           double initial, String color) {
        check(name != null && name.trim().length() > 0, "账户名称不能为空");
        String id = String.format(Locale.US, "%06x", (int) (Math.random() * 0x1000000));
        while (findAccount(id) != null) {
            id = String.format(Locale.US, "%06x", (int) (Math.random() * 0x1000000));
        }
        Account a = new Account(id, name.trim(), tag == null ? "" : tag.trim(),
                cleanTail(tail), initial, color == null || color.length() == 0 ? "#64748b" : color);
        accounts.add(a);
        save();
        return a;
    }

    private static String cleanTail(String tail) {
        if (tail == null) {
            return "";
        }
        String d = tail.replaceAll("\\D", "");
        return d.length() > 6 ? d.substring(d.length() - 6) : d;
    }

    public synchronized Account updateAccount(String id, String name, String tag, String tail,
                                              Double initial, String color) {
        Account a = findAccount(id);
        check(a != null, "账户不存在");
        if (name != null && name.trim().length() > 0) {
            a.name = name.trim();
        }
        if (tag != null) {
            a.tag = tag.trim();
        }
        if (tail != null) {
            a.tail = cleanTail(tail);
        }
        if (initial != null) {
            a.initial = Math.round(initial * 100) / 100.0;
        }
        if (color != null && color.length() > 0) {
            a.color = color;
        }
        save();
        return a;
    }

    public synchronized void deleteAccount(String id) {
        for (Tx t : txs) {
            if (t.account.equals(id) || t.to.equals(id)) {
                throw new IllegalArgumentException("该账户已有流水，不能删除（可改名或把期初余额调为 0）");
            }
        }
        Account a = findAccount(id);
        check(a != null, "账户不存在");
        accounts.remove(a);
        save();
    }

    public synchronized void setBudget(String category, double limit) {
        boolean known = false;
        for (String c : expenseCategories) {
            if (c.equals(category)) {
                known = true;
            }
        }
        check(known, "分类不存在");
        if (limit <= 0) {
            budgets.remove(category);
        } else {
            budgets.put(category, Math.round(limit * 100) / 100.0);
        }
        save();
    }

    // ---------------- 概览 ----------------

    public synchronized String summaryJson(String month) {
        double income = 0, expense = 0;
        Map<String, Double> byCat = new LinkedHashMap<String, Double>();
        Map<String, Double> byAcc = new LinkedHashMap<String, Double>();
        for (Tx t : txs) {
            if (month != null && month.length() > 0 && !monthOf(t).equals(month)) {
                continue;
            }
            if (t.type.equals("income")) {
                income += t.amount;
            } else if (t.type.equals("expense")) {
                expense += t.amount;
                byCat.put(t.category, (byCat.containsKey(t.category) ? byCat.get(t.category) : 0) + t.amount);
                byAcc.put(t.account, (byAcc.containsKey(t.account) ? byAcc.get(t.account) : 0) + t.amount);
            }
        }
        double budgetTotal = 0;
        for (String c : expenseCategories) {
            if (budgets.containsKey(c)) {
                budgetTotal += budgets.get(c);
            }
        }
        StringBuilder cats = new StringBuilder();
        List<String[]> rows = new ArrayList<String[]>();
        for (String c : expenseCategories) {
            double limit = budgets.containsKey(c) ? budgets.get(c) : 0;
            double used = byCat.containsKey(c) ? byCat.get(c) : 0;
            if (limit <= 0 && used <= 0) {
                continue;
            }
            rows.add(new String[]{c, fmt(limit), fmt(used),
                    limit > 0 ? String.valueOf(Math.round(used / limit * 1000) / 10.0) : "null"});
        }
        Collections.sort(rows, new Comparator<String[]>() {
            @Override
            public int compare(String[] x, String[] y) {
                double px = "null".equals(x[3]) ? -1 : Double.parseDouble(x[3]);
                double py = "null".equals(y[3]) ? -1 : Double.parseDouble(y[3]);
                if (px != py) {
                    return Double.compare(py, px);
                }
                return Double.compare(Double.parseDouble(y[2]), Double.parseDouble(x[2]));
            }
        });
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (i > 0) {
                cats.append(',');
            }
            cats.append("{\"category\":\"").append(jsonEscape(r[0]))
                    .append("\",\"limit\":").append(r[1])
                    .append(",\"used\":").append(r[2])
                    .append(",\"pct\":").append(r[3]).append('}');
        }
        StringBuilder byAccJson = new StringBuilder();
        for (Map.Entry<String, Double> e : byAcc.entrySet()) {
            if (byAccJson.length() > 0) {
                byAccJson.append(',');
            }
            byAccJson.append('"').append(jsonEscape(e.getKey())).append("\":").append(fmt(e.getValue()));
        }
        return "{\"month\":\"" + jsonEscape(month) + "\""
                + ",\"income\":" + fmt(income)
                + ",\"expense\":" + fmt(expense)
                + ",\"balance\":" + fmt(income - expense)
                + ",\"budget_total\":" + fmt(budgetTotal)
                + ",\"categories\":[" + cats + "]"
                + ",\"by_account\":{" + byAccJson + "}}";
    }

    public synchronized String stateJson(String month) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"accounts\":[");
        for (int i = 0; i < accounts.size(); i++) {
            Account a = accounts.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"").append(jsonEscape(a.id))
                    .append("\",\"name\":\"").append(jsonEscape(a.name))
                    .append("\",\"tag\":\"").append(jsonEscape(a.tag))
                    .append("\",\"tail\":\"").append(jsonEscape(a.tail))
                    .append("\",\"initial\":").append(fmt(a.initial))
                    .append(",\"color\":\"").append(jsonEscape(a.color))
                    .append("\",\"balance\":").append(fmt(balanceOf(a.id)))
                    .append(",\"adj\":").append(fmt(pendingAdjustSum(a.id)))
                    .append(",\"adjCount\":").append(pendingAdjustCount(a.id)).append('}');
        }
        sb.append("],\"expense_categories\":[");
        for (int i = 0; i < expenseCategories.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(jsonEscape(expenseCategories[i])).append('"');
        }
        sb.append("],\"income_categories\":[");
        for (int i = 0; i < incomeCategories.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(jsonEscape(incomeCategories[i])).append('"');
        }
        sb.append("],\"budgets\":{");
        boolean first = true;
        for (Map.Entry<String, Double> e : budgets.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(jsonEscape(e.getKey())).append("\":").append(fmt(e.getValue()));
        }
        sb.append("},\"summary\":").append(summaryJson(month));
        sb.append(",\"transactions\":[");
        List<Tx> sorted = new ArrayList<Tx>(txs);
        Collections.sort(sorted, new Comparator<Tx>() {
            @Override
            public int compare(Tx x, Tx y) {
                String kx = x.date + " " + x.created;
                String ky = y.date + " " + y.created;
                return ky.compareTo(kx);
            }
        });
        for (int i = 0; i < sorted.size(); i++) {
            Tx t = sorted.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"").append(jsonEscape(t.id))
                    .append("\",\"type\":\"").append(t.type)
                    .append("\",\"account\":\"").append(jsonEscape(t.account))
                    .append("\",\"to\":\"").append(jsonEscape(t.to))
                    .append("\",\"category\":\"").append(jsonEscape(t.category))
                    .append("\",\"amount\":").append(fmt(t.amount))
                    .append(",\"date\":\"").append(t.date)
                    .append("\",\"note\":\"").append(jsonEscape(t.note))
                    .append("\",\"created\":\"").append(jsonEscape(t.created)).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    public synchronized String exportJson() {
        return stateJson("");
    }

    // ---------------- 自动入账 ----------------

    public synchronized Incoming handleIncoming(String kind, String pkg, String title,
                                                String text, boolean force) {
        String all = (title == null ? "" : title) + " " + (text == null ? "" : text);
        Parser.Parsed p = Parser.parse(all);
        if (p == null) {
            // 余额-only 短信也要用来校准（可能正好暴露漏记的免密支付）
            calibrateIfPossible(all);
            return new Incoming("skipped", "没有识别出金额", null);
        }
        String key = p.type + ":" + fmt(p.amount);
        long nowMs = System.currentTimeMillis();
        if (!force) {
            for (String k : new ArrayList<String>(dedup.keySet())) {
                if (nowMs - dedup.get(k) > 300000L) {
                    dedup.remove(k);
                }
            }
            if (dedup.containsKey(key)) {
                calibrateIfPossible(all);
                return new Incoming("duplicate", "5分钟内同额已入账", null);
            }
            dedup.put(key, nowMs);
        }

        String accId = resolveAccount(all);
        String cat = p.type.equals("income")
                ? Parser.guessIncomeCat(all) : Parser.guessCategory(all, p.merchant);
        String note = p.merchant.length() > 0 ? p.merchant
                : (pkg == null || pkg.length() == 0 ? (kind == null ? "自动识别" : kind) : pkg);
        Tx t = addTx(p.type, accId, "", cat, p.amount, today(), "[自动]" + note);
        // 短信里的余额是本笔之后的余额，入账后再校准期初
        calibrateIfPossible(all);
        return new Incoming("inserted", "", t);
    }

    private void calibrateIfPossible(String all) {
        double snapshot = Parser.balanceOf(all);
        if (snapshot <= 0) {
            return;
        }
        if (Parser.bankOf(all) == null && tailMatch(all) == null) {
            return;
        }
        applyBalance(resolveAccount(all), snapshot);
    }

    private String tailMatch(String text) {
        for (Account a : accounts) {
            if (a.tail != null && a.tail.length() >= 4 && text.contains(a.tail)) {
                return a.id;
            }
        }
        return null;
    }

    private String resolveAccount(String text) {
        String bank = Parser.bankOf(text);
        if (bank != null && findAccount(bank) != null) {
            return bank;
        }
        String tail = tailMatch(text);
        if (tail != null) {
            return tail;
        }
        if (findAccount("boc") != null) {
            return "boc";
        }
        return accounts.get(0).id;
    }

    /**
     * 银行短信报出真实余额 → 校准期初。
     * 首次校准=静默建立基线（修正手填期初）；之后发现差额=疑似漏记（如免密支付），记为待补差额。
     */
    public synchronized void applyBalance(String accId, double snapshot) {
        Account a = findAccount(accId);
        if (a == null) {
            return;
        }
        double computed = Math.round((a.initial + deltaOf(accId)) * 100) / 100.0;
        double diff = Math.round((snapshot - computed) * 100) / 100.0;
        if (a.calibrated && Math.abs(diff) >= 0.01) {
            List<Adj> list = adjusts.get(accId);
            if (list == null) {
                list = new ArrayList<Adj>();
                adjusts.put(accId, list);
            }
            list.add(new Adj(diff, today()));
        }
        a.initial = Math.round((snapshot - deltaOf(accId)) * 100) / 100.0;
        a.calibrated = true;
        save();
    }

    public synchronized double pendingAdjustSum(String accId) {
        List<Adj> list = adjusts.get(accId);
        if (list == null) {
            return 0;
        }
        double sum = 0;
        for (Adj adj : list) {
            sum += adj.diff;
        }
        return Math.round(sum * 100) / 100.0;
    }

    public synchronized int pendingAdjustCount(String accId) {
        List<Adj> list = adjusts.get(accId);
        return list == null ? 0 : list.size();
    }

    /**
     * 处理待补差额：record=按差额方向补成流水（同时反向调期初，保证余额不变）；ignore=清除。
     * 返回补记的条数。
     */
    public synchronized int resolveAdjust(String accId, String action) {
        Account a = findAccount(accId);
        check(a != null, "账户不存在");
        List<Adj> list = adjusts.get(accId);
        check(list != null && !list.isEmpty(), "没有待处理差额");
        if ("ignore".equals(action)) {
            adjusts.remove(accId);
            save();
            return 0;
        }
        double impact = 0;
        int n = 0;
        for (Adj adj : list) {
            double amt = Math.abs(adj.diff);
            if (amt < 0.01) {
                continue;
            }
            boolean exp = adj.diff < 0;
            addTx(exp ? "expense" : "income", accId, "",
                    exp ? "其他" : "其他收入", amt, today(), "[差额补记] " + adj.date);
            impact += exp ? -amt : amt;
            n++;
        }
        a.initial = Math.round((a.initial - impact) * 100) / 100.0;
        adjusts.remove(accId);
        save();
        return n;
    }
}
