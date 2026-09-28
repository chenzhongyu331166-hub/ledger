package com.zxy.ledgerlink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯 Java 解析器（不依赖 Android，可在 JVM 上直接跑单元测试）。
 * 从银行短信 / 微信支付宝通知 / 屏幕文字里解析：方向、金额、商户、银行、余额。
 */
public class Parser {

    public static class Parsed {
        public final String type;      // expense / income
        public final double amount;
        public final String merchant;

        public Parsed(String type, double amount, String merchant) {
            this.type = type;
            this.amount = amount;
            this.merchant = merchant;
        }
    }

    static final String[] SKIP = {"充值", "提现", "零钱通", "余额宝", "转账到",
            "还款提醒", "验证码", "登录", "红包已领取"};
    static final String[] INCOME_WORDS = {"收入", "转入", "存入", "入账", "到账",
            "退款", "收款", "工资", "工资卡", "进账"};

    static final Pattern DATE_RE = Pattern.compile(
            "\\d{4}年|\\d{1,2}月\\d{1,2}[日号]?|\\d{1,2}[日号]|\\d{1,2}:\\d{2}(?::\\d{2})?|星期[一二三四五六日天]");

    static final String INC_KEYS = "收入|转入|存入|入账|到账|退款|收款|进账|工资";
    static final String EXP_KEYS = "支出|扣款|付款|消费|支付|购买|取出|转出|订单|手续费";
    static final Pattern KEY_INC = Pattern.compile(
            "(" + INC_KEYS + ")[^0-9]{0,18}[¥￥]?\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)");
    static final Pattern KEY_EXP = Pattern.compile(
            "(" + EXP_KEYS + ")[^0-9]{0,18}[¥￥]?\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)");
    static final Pattern MONEY = Pattern.compile(
            "[¥￥]\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)|(\\d[\\d,]*(?:\\.\\d{1,2})?)\\s*元");

    static final Pattern BANK_CMB = Pattern.compile("招商|招行|CMB", Pattern.CASE_INSENSITIVE);
    static final Pattern BANK_CCB = Pattern.compile("建设|建行|CCB", Pattern.CASE_INSENSITIVE);
    static final Pattern BANK_BOC = Pattern.compile("中国银行|中行|BOC", Pattern.CASE_INSENSITIVE);

    static final Pattern BALANCE_RE = Pattern.compile(
            "(?:余额|可用余额|活期余额|账户余额)[：:]?\\s*[¥￥]?\\s*(\\d[\\d,]*\\.\\d{2})");

    static final String[][] CAT_RULES = {
            {"餐饮", "美团,饿了么,肯德基,KFC,麦当劳,星巴克,瑞幸,奶茶,餐厅,饭店,外卖,小吃,咖啡,食堂,蜜雪,喜茶,海底捞"},
            {"交通", "滴滴,地铁,公交,高铁,12306,铁路,加油,中石化,中石油,出租,停车,航空,机票,单车,高德"},
            {"购物", "淘宝,天猫,京东,拼多多,唯品会,苏宁,网购,商城,闲鱼"},
            {"日用", "超市,便利店,沃尔玛,永辉,大润发,全家,罗森,盒马,日用,7-11,家乐福"},
            {"居住", "房租,租金,水费,电费,燃气,物业,暖气,宽带,话费,流量"},
            {"学习", "图书,书店,教材,网课,课程,培训,考试,打印,文具"},
            {"娱乐", "游戏,视频,会员,爱奇艺,腾讯视频,B站,哔哩,网易云,电影,KTV,健身,盲盒"},
            {"医疗", "医院,药店,药房,诊所,体检,门诊,口腔"},
            {"人情", "随礼,份子,礼金"},
    };
    static final String[][] INCOME_CAT_RULES = {
            {"工资", "工资,薪资,代发,发薪"},
            {"奖金", "奖金,绩效,年终"},
            {"红包", "红包,转账给你"},
            {"理财收益", "利息,收益,分红"},
    };

    static final Pattern[] MERCHANT_PATS = {
            Pattern.compile("商户[：:]?\\s*([^\\s，,；;元]{2,20})"),
            Pattern.compile("「([^」]+)」"),
            Pattern.compile("向[\"']([^\"']+)[\"']付款"),
            Pattern.compile("付款给\\s*([^\\s，,]+)"),
            Pattern.compile("(?:来自|收款方)[：:]?\\s*([^\\s，,]+)"),
    };

    private static class Pick {
        final int pos;
        final int order; // 0 = income 优先
        final String type;
        final String amountStr;

        Pick(int pos, int order, String type, String amountStr) {
            this.pos = pos;
            this.order = order;
            this.type = type;
            this.amountStr = amountStr;
        }
    }

    public static Parsed parse(String text) {
        if (text == null || text.length() == 0) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ");
        for (String w : SKIP) {
            if (flat.contains(w)) {
                return null;
            }
        }
        String cleaned = DATE_RE.matcher(flat).replaceAll(" ");

        List<Pick> cands = new ArrayList<Pick>();
        collect(KEY_INC, cleaned, "income", cands);
        collect(KEY_EXP, cleaned, "expense", cands);
        String type;
        String amountStr;
        if (!cands.isEmpty()) {
            Collections.sort(cands, new Comparator<Pick>() {
                @Override
                public int compare(Pick a, Pick b) {
                    if (a.pos != b.pos) {
                        return a.pos - b.pos;
                    }
                    return a.order - b.order;
                }
            });
            type = cands.get(0).type;
            amountStr = cands.get(0).amountStr;
        } else {
            Matcher m2 = MONEY.matcher(cleaned);
            if (!m2.find()) {
                return null;
            }
            amountStr = m2.group(1) != null ? m2.group(1) : m2.group(2);
            if (containsAny(cleaned, INCOME_WORDS)) {
                type = "income";
            } else if (cleaned.contains("¥") || cleaned.contains("￥")
                    || Pattern.compile("成功|已付|实付|票价|订单|购买|下单").matcher(cleaned).find()) {
                type = "expense";
            } else {
                return null;
            }
        }
        double amount;
        try {
            amount = Double.parseDouble(amountStr.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
        if (amount <= 0 || amount > 1000000) {
            return null;
        }
        amount = Math.round(amount * 100) / 100.0;

        String merchant = "";
        for (Pattern p : MERCHANT_PATS) {
            Matcher mm = p.matcher(flat);
            if (mm.find()) {
                merchant = mm.group(1);
                if (merchant.length() > 24) {
                    merchant = merchant.substring(0, 24);
                }
                break;
            }
        }
        return new Parsed(type, amount, merchant);
    }

    private static void collect(Pattern p, String s, String type, List<Pick> out) {
        Matcher m = p.matcher(s);
        while (m.find()) {
            out.add(new Pick(m.start(2), "income".equals(type) ? 0 : 1, type, m.group(2)));
        }
    }

    private static boolean containsAny(String s, String[] arr) {
        for (String w : arr) {
            if (s.contains(w)) {
                return true;
            }
        }
        return false;
    }

    public static boolean looksLikeMoney(String s) {
        if (s == null || s.length() == 0) {
            return false;
        }
        return Pattern.compile("(\\d+(\\.\\d{1,2})?\\s*元|[¥￥]\\s*\\d+(\\.\\d{1,2})?"
                + "|支出|扣款|付款|收入|到账|交易|支付|余额)").matcher(s).find();
    }

    /** 文本里出现的银行 → cmb/ccb/boc，识别不出返回 null。 */
    public static String bankOf(String text) {
        if (text == null) {
            return null;
        }
        if (BANK_CMB.matcher(text).find()) {
            return "cmb";
        }
        if (BANK_CCB.matcher(text).find()) {
            return "ccb";
        }
        if (BANK_BOC.matcher(text).find()) {
            return "boc";
        }
        return null;
    }

    /** 银行短信里的余额快照（如 "余额3,000.00元"），没有返回 0。 */
    public static double balanceOf(String text) {
        if (text == null) {
            return 0;
        }
        Matcher m = BALANCE_RE.matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    public static String guessCategory(String text, String merchant) {
        String blob = (text == null ? "" : text) + " " + (merchant == null ? "" : merchant);
        String low = blob.toLowerCase();
        for (String[] rule : CAT_RULES) {
            for (String kw : rule[1].split(",")) {
                if (low.contains(kw.toLowerCase())) {
                    return rule[0];
                }
            }
        }
        return "其他";
    }

    public static String guessIncomeCat(String text) {
        for (String[] rule : INCOME_CAT_RULES) {
            for (String kw : rule[1].split(",")) {
                if (text != null && text.contains(kw)) {
                    return rule[0];
                }
            }
        }
        return "其他收入";
    }
}
