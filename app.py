# -*- coding: utf-8 -*-
"""本地记账工具：收支/转账/账户余额/分类预算进度条。纯标准库，无依赖。"""
import json
import os
import re
import socket
import threading
import uuid
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

BASE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE, "data")
DATA_FILE = os.path.join(DATA_DIR, "ledger.json")
STATIC_DIR = os.path.join(BASE, "static")
PORT = 5100

DEFAULT = {
    "accounts": [
        {"id": "cmb", "name": "招商银行", "tag": "工资卡", "initial": 0.0, "color": "#e11d48"},
        {"id": "ccb", "name": "建设银行", "tag": "工资卡", "initial": 0.0, "color": "#1d4ed8"},
        {"id": "boc", "name": "中国银行", "tag": "主力消费", "initial": 0.0, "color": "#b45309"},
    ],
    "expense_categories": ["餐饮", "交通", "购物", "日用", "娱乐", "居住", "学习", "医疗", "人情", "其他"],
    "income_categories": ["工资", "奖金", "兼职工资", "理财收益", "红包", "其他收入"],
    "budgets": {},
    "transactions": [],
}

_lock = threading.Lock()


def load_state():
    if not os.path.exists(DATA_FILE):
        return json.loads(json.dumps(DEFAULT, ensure_ascii=False))
    with open(DATA_FILE, "r", encoding="utf-8") as f:
        state = json.load(f)
    for key, val in DEFAULT.items():
        state.setdefault(key, json.loads(json.dumps(val, ensure_ascii=False)))
    return state


def save_state(state):
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp = DATA_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)
    os.replace(tmp, DATA_FILE)


STATE = load_state()


def fnum(v):
    return round(float(v) + 0.0, 2)


def account_balance(state, acc_id):
    acc = next((a for a in state["accounts"] if a["id"] == acc_id), None)
    if not acc:
        return 0.0
    bal = fnum(acc.get("initial", 0))
    for t in state["transactions"]:
        amt = fnum(t["amount"])
        if t["type"] == "income" and t["account"] == acc_id:
            bal += amt
        elif t["type"] == "expense" and t["account"] == acc_id:
            bal -= amt
        elif t["type"] == "transfer":
            if t["account"] == acc_id:
                bal -= amt
            if t.get("to") == acc_id:
                bal += amt
    return fnum(bal)


def month_of(t):
    return (t.get("date") or "")[:7]


def build_summary(state, month):
    income = expense = 0.0
    by_cat = {}
    by_acc = {}
    for t in state["transactions"]:
        if month and month_of(t) != month:
            continue
        amt = fnum(t["amount"])
        if t["type"] == "income":
            income += amt
        elif t["type"] == "expense":
            expense += amt
            by_cat[t["category"]] = fnum(by_cat.get(t["category"], 0) + amt)
            by_acc[t["account"]] = fnum(by_acc.get(t["account"], 0) + amt)
    budgets = state.get("budgets", {})
    cats = []
    for name in state["expense_categories"]:
        limit = fnum(budgets.get(name, 0))
        used = fnum(by_cat.get(name, 0))
        if limit <= 0 and used <= 0:
            continue
        pct = round(used / limit * 100, 1) if limit > 0 else None
        cats.append({"category": name, "limit": limit, "used": used, "pct": pct})
    cats.sort(key=lambda c: (-(c["pct"] or 0), -c["used"]))
    return {
        "month": month,
        "income": fnum(income),
        "expense": fnum(expense),
        "balance": fnum(income - expense),
        "budget_total": fnum(sum(fnum(budgets.get(c, 0)) for c in state["expense_categories"])),
        "categories": cats,
        "by_account": by_acc,
    }


def accounts_view(state):
    out = []
    for a in state["accounts"]:
        d = dict(a)
        d["balance"] = account_balance(state, a["id"])
        out.append(d)
    return out


# ---------------- 手机上报自动入账 ----------------
NOTIFY_LOG = os.path.join(DATA_DIR, "notify_log.jsonl")
SKIP_WORDS = ["充值", "提现", "零钱通", "余额宝", "转账到", "还款提醒", "验证码", "登录", "红包已领取"]
INCOME_WORDS = ["收入", "转入", "存入", "入账", "到账", "退款", "收款", "工资", "工资卡", "进账"]
EXPENSE_WORDS = ["支出", "扣款", "付款", "消费", "支付", "购买", "取出", "转出", "订单", "手续费"]
DATE_RE = re.compile(r"\d{4}年|\d{1,2}月\d{1,2}[日号]?|\d{1,2}[日号]|\d{1,2}:\d{2}(?::\d{2})?|星期[一二三四五六日天]")
_INC_KEYS = "收入|转入|存入|入账|到账|退款|收款|进账|工资"
_EXP_KEYS = "支出|扣款|付款|消费|支付|购买|取出|转出|订单|手续费"
KEY_INC_RE = re.compile(r"(%s)[^0-9]{0,18}[¥￥]?\s*(\d[\d,]*(?:\.\d{1,2})?)" % _INC_KEYS)
KEY_EXP_RE = re.compile(r"(%s)[^0-9]{0,18}[¥￥]?\s*(\d[\d,]*(?:\.\d{1,2})?)" % _EXP_KEYS)
BANK_MAP = [
    (re.compile(r"招商|招行|CMB", re.I), "cmb"),
    (re.compile(r"建设|建行|CCB", re.I), "ccb"),
    (re.compile(r"中国银行|中行|BOC", re.I), "boc"),
]
CAT_RULES = [
    ("餐饮", ["美团", "饿了么", "肯德基", "KFC", "麦当劳", "星巴克", "瑞幸", "奶茶", "餐厅", "饭店", "外卖", "小吃", "咖啡", "食堂", "蜜雪", "喜茶", "海底捞"]),
    ("交通", ["滴滴", "地铁", "公交", "高铁", "12306", "铁路", "加油", "中石化", "中石油", "出租", "停车", "航空", "机票", "单车", "高德"]),
    ("购物", ["淘宝", "天猫", "京东", "拼多多", "唯品会", "苏宁", "网购", "商城", "闲鱼"]),
    ("日用", ["超市", "便利店", "沃尔玛", "永辉", "大润发", "全家", "罗森", "盒马", "日用", "7-11", "家乐福"]),
    ("居住", ["房租", "租金", "水费", "电费", "燃气", "物业", "暖气", "宽带", "话费", "流量"]),
    ("学习", ["图书", "书店", "教材", "网课", "课程", "培训", "考试", "打印", "文具"]),
    ("娱乐", ["游戏", "视频", "会员", "爱奇艺", "腾讯视频", "B站", "哔哩", "网易云", "电影", "KTV", "健身", "盲盒"]),
    ("医疗", ["医院", "药店", "药房", "诊所", "体检", "门诊", "口腔"]),
    ("人情", ["随礼", "份子", "礼金"]),
]
INCOME_CAT_RULES = [
    ("工资", ["工资", "薪资", "代发", "发薪"]),
    ("奖金", ["奖金", "绩效", "年终"]),
    ("红包", ["红包", "转账给你"]),
    ("理财收益", ["利息", "收益", "分红"]),
]
MONEY_RE = re.compile(r"[¥￥]\s*(\d[\d,]*(?:\.\d{1,2})?)|(\d[\d,]*(?:\.\d{1,2})?)\s*元")
_dedup = {}
_dedup_lock = threading.Lock()


def _log_notify(rec):
    try:
        os.makedirs(DATA_DIR, exist_ok=True)
        lines = []
        if os.path.exists(NOTIFY_LOG):
            with open(NOTIFY_LOG, "r", encoding="utf-8") as f:
                lines = f.readlines()
        lines.append(json.dumps(rec, ensure_ascii=False) + "\n")
        with open(NOTIFY_LOG, "w", encoding="utf-8") as f:
            f.writelines(lines[-300:])
    except Exception:
        pass


def parse_payment(text):
    """从短信/通知/屏幕文本里解析出 (type, amount, merchant)，解析不出返回 None。"""
    if not text:
        return None
    flat = re.sub(r"\s+", " ", text)
    for w in SKIP_WORDS:
        if w in flat:
            return None
    cleaned = DATE_RE.sub(" ", flat)
    cands = []
    for rx, is_inc in ((KEY_INC_RE, True), (KEY_EXP_RE, False)):
        for mm in rx.finditer(cleaned):
            cands.append((mm.start(2), 0 if is_inc else 1, is_inc, mm.group(2)))
    if cands:
        cands.sort()
        typ = "income" if cands[0][2] else "expense"
        amount = float(cands[0][3].replace(",", ""))
    else:
        m2 = MONEY_RE.search(cleaned)
        if not m2:
            return None
        amount = float((m2.group(1) or m2.group(2)).replace(",", ""))
        if any(w in cleaned for w in INCOME_WORDS):
            typ = "income"
        elif re.search(r"成功|已付|实付|票价|订单|购买|下单", cleaned) or "¥" in cleaned or "￥" in cleaned:
            typ = "expense"
        else:
            return None
    if amount <= 0 or amount > 1_000_000:
        return None
    merchant = ""
    for pat in (r"商户[：:]?\s*([^\s，,；;元]{2,20})", r"「([^」]+)」", r"向[""\']([^""\']+)[""\']付款",
                r"付款给\s*([^\s，,]+)", r"(?:来自|收款方)[：:]?\s*([^\s，,]+)"):
        mm = re.search(pat, flat)
        if mm:
            merchant = mm.group(1)[:24]
            break
    return typ, amount, merchant


def guess_category(text, merchant):
    blob = (text or "") + " " + (merchant or "")
    for cat, kws in CAT_RULES:
        for kw in kws:
            if kw.lower() in blob.lower():
                return cat
    return "其他"


def guess_income_cat(text):
    for cat, kws in INCOME_CAT_RULES:
        for kw in kws:
            if kw in text:
                return cat
    return "其他收入"


def guess_account(state, text):
    for pat, acc_id in BANK_MAP:
        if pat.search(text or ""):
            if any(a["id"] == acc_id for a in state["accounts"]):
                return acc_id
    for a in state["accounts"]:
        tail = str(a.get("tail") or "")
        if tail and tail in (text or ""):
            return a["id"]
    return "boc" if any(a["id"] == "boc" for a in state["accounts"]) else state["accounts"][0]["id"]


def is_duplicate(typ, amount):
    now = datetime.now().timestamp()
    with _dedup_lock:
        for k in list(_dedup):
            if now - _dedup[k] > 300:
                _dedup.pop(k, None)
        key = (typ, round(amount, 2))
        if key in _dedup:
            return True
        _dedup[key] = now
        return False


def handle_notify(payload):
    text = " ".join(str(payload.get(k) or "") for k in ("title", "text"))
    pkg = str(payload.get("pkg") or "")
    rec = {"ts": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "pkg": pkg,
           "kind": payload.get("kind"), "text": text[:300]}
    parsed = parse_payment(text)
    if not parsed:
        rec["result"] = "skip:no-pattern"
        _log_notify(rec)
        return {"ok": True, "action": "skipped", "reason": "没有识别出金额"}
    typ, amount, merchant = parsed
    if is_duplicate(typ, amount):
        rec["result"] = "skip:duplicate"
        _log_notify(rec)
        return {"ok": True, "action": "duplicate", "reason": "5分钟内同额已入账"}
    acc_id = guess_account(STATE, text)
    if typ == "income":
        cat = guess_income_cat(text)
    else:
        cat = guess_category(text, merchant)
    note = merchant or pkg or "自动识别"
    note = ("[自动]" + note)[:120]
    t, err = apply_transaction({
        "type": typ, "amount": amount, "date": datetime.now().strftime("%Y-%m-%d"),
        "account": acc_id, "category": cat, "note": note,
    })
    if err:
        rec["result"] = "error:" + err
        _log_notify(rec)
        return {"ok": False, "error": err}
    STATE["transactions"].append(t)
    save_state(STATE)
    rec["result"] = "inserted:%s:%s:%s" % (typ, amount, cat)
    _log_notify(rec)
    return {"ok": True, "action": "inserted", "transaction": t}


def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("223.5.5.5", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def validate_payload(p):
    t = p.get("type")
    if t not in ("expense", "income", "transfer"):
        return "type 必须是 expense/income/transfer"
    try:
        amt = fnum(p.get("amount"))
    except (TypeError, ValueError):
        return "金额必须是数字"
    if amt <= 0:
        return "金额必须大于 0"
    acc_ids = [a["id"] for a in STATE["accounts"]]
    if p.get("account") not in acc_ids:
        return "请选择账户"
    if t == "transfer":
        if p.get("to") not in acc_ids:
            return "请选择转入账户"
        if p["to"] == p["account"]:
            return "转出和转入账户不能相同"
    if t in ("expense", "income") and not (p.get("category") or "").strip():
        return "请选择分类"
    date = (p.get("date") or "").strip()
    try:
        datetime.strptime(date, "%Y-%m-%d")
    except ValueError:
        return "日期格式应为 YYYY-MM-DD"
    return None


def apply_transaction(p, tx_id=None):
    err = validate_payload(p)
    if err:
        return None, err
    t = {
        "id": tx_id or uuid.uuid4().hex[:12],
        "type": p["type"],
        "account": p["account"],
        "to": p.get("to") or "",
        "category": (p.get("category") or "").strip(),
        "amount": fnum(p["amount"]),
        "date": p["date"],
        "note": (p.get("note") or "").strip()[:120],
        "created": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    }
    return t, None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    # ---------- helpers ----------
    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        ln = int(self.headers.get("Content-Length") or 0)
        if ln <= 0:
            return {}
        raw = self.rfile.read(ln)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    def _file(self, path, ctype):
        if not os.path.isfile(path):
            self._json({"error": "not found"}, 404)
            return
        with open(path, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    # ---------- routes ----------
    def do_GET(self):
        u = urlparse(self.path)
        path = u.path
        q = parse_qs(u.query)
        if path == "/":
            return self._file(os.path.join(STATIC_DIR, "index.html"), "text/html; charset=utf-8")
        if path.startswith("/static/"):
            rel = path[len("/static/"):]
            safe = os.path.normpath(rel).replace("/", os.sep)
            return self._file(os.path.join(STATIC_DIR, safe), "text/plain; charset=utf-8")
        if path == "/api/state":
            with _lock:
                month = (q.get("month") or [""])[0] or datetime.now().strftime("%Y-%m")
                return self._json({
                    "accounts": accounts_view(STATE),
                    "expense_categories": STATE["expense_categories"],
                    "income_categories": STATE["income_categories"],
                    "budgets": STATE["budgets"],
                    "summary": build_summary(STATE, month),
                    "transactions": sorted(STATE["transactions"], key=lambda t: (t["date"], t["created"]), reverse=True),
                })
        if path == "/api/lan":
            return self._json({"ip": lan_ip(), "port": PORT})
        if path == "/app.apk":
            apk = os.path.join(BASE, "mobile", "app.apk")
            if not os.path.exists(apk):
                return self._json({"error": "APK 还没构建"}, 404)
            with open(apk, "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Content-Disposition", 'attachment; filename="ledger-assistant.apk"')
            self.end_headers()
            self.wfile.write(data)
            return
        self._json({"error": "not found"}, 404)

    def do_POST(self):
        u = urlparse(self.path)
        p = self._body()
        with _lock:
            if u.path == "/api/notify":
                return self._json(handle_notify(p))
            if u.path == "/api/transaction":
                t, err = apply_transaction(p)
                if err:
                    return self._json({"error": err}, 400)
                STATE["transactions"].append(t)
                save_state(STATE)
                return self._json({"ok": True, "transaction": t})
            if u.path == "/api/account":
                name = (p.get("name") or "").strip()
                if not name:
                    return self._json({"error": "账户名称不能为空"}, 400)
                acc = {
                    "id": uuid.uuid4().hex[:8],
                    "name": name,
                    "tag": (p.get("tag") or "").strip()[:20],
                    "initial": fnum(p.get("initial") or 0),
                    "color": p.get("color") or "#64748b",
                    "tail": re.sub(r"\D", "", str(p.get("tail") or ""))[-6:],
                }
                STATE["accounts"].append(acc)
                save_state(STATE)
                return self._json({"ok": True, "account": acc})
            if u.path == "/api/budget":
                cat = (p.get("category") or "").strip()
                if cat not in STATE["expense_categories"]:
                    return self._json({"error": "分类不存在"}, 400)
                try:
                    limit = fnum(p.get("limit"))
                except (TypeError, ValueError):
                    return self._json({"error": "预算必须是数字"}, 400)
                if limit <= 0:
                    STATE["budgets"].pop(cat, None)
                else:
                    STATE["budgets"][cat] = limit
                save_state(STATE)
                return self._json({"ok": True, "budgets": STATE["budgets"]})
        self._json({"error": "not found"}, 404)

    def do_PUT(self):
        u = urlparse(self.path)
        p = self._body()
        with _lock:
            parts = u.path.strip("/").split("/")
            if len(parts) == 3 and parts[1] == "transaction":
                tx_id = parts[2]
                idx = next((i for i, t in enumerate(STATE["transactions"]) if t["id"] == tx_id), None)
                if idx is None:
                    return self._json({"error": "记录不存在"}, 404)
                t, err = apply_transaction(p, tx_id)
                if err:
                    return self._json({"error": err}, 400)
                t["created"] = STATE["transactions"][idx]["created"]
                STATE["transactions"][idx] = t
                save_state(STATE)
                return self._json({"ok": True, "transaction": t})
            if len(parts) == 3 and parts[1] == "account":
                acc_id = parts[2]
                acc = next((a for a in STATE["accounts"] if a["id"] == acc_id), None)
                if not acc:
                    return self._json({"error": "账户不存在"}, 404)
                if (p.get("name") or "").strip():
                    acc["name"] = p["name"].strip()
                if p.get("tag") is not None:
                    acc["tag"] = str(p["tag"]).strip()[:20]
                if p.get("initial") is not None:
                    try:
                        acc["initial"] = fnum(p["initial"])
                    except (TypeError, ValueError):
                        return self._json({"error": "期初余额必须是数字"}, 400)
                if p.get("color"):
                    acc["color"] = p["color"]
                if p.get("tail") is not None:
                    acc["tail"] = re.sub(r"\D", "", str(p["tail"]))[-6:]
                save_state(STATE)
                return self._json({"ok": True, "account": acc})
        self._json({"error": "not found"}, 404)

    def do_DELETE(self):
        u = urlparse(self.path)
        parts = u.path.strip("/").split("/")
        with _lock:
            if len(parts) == 3 and parts[1] == "transaction":
                before = len(STATE["transactions"])
                STATE["transactions"] = [t for t in STATE["transactions"] if t["id"] != parts[2]]
                if len(STATE["transactions"]) == before:
                    return self._json({"error": "记录不存在"}, 404)
                save_state(STATE)
                return self._json({"ok": True})
            if len(parts) == 3 and parts[1] == "account":
                acc_id = parts[2]
                used = any(t["account"] == acc_id or t.get("to") == acc_id for t in STATE["transactions"])
                if used:
                    return self._json({"error": "该账户已有流水，不能删除（可改名或把期初余额调为 0）"}, 400)
                before = len(STATE["accounts"])
                STATE["accounts"] = [a for a in STATE["accounts"] if a["id"] != acc_id]
                if len(STATE["accounts"]) == before:
                    return self._json({"error": "账户不存在"}, 404)
                save_state(STATE)
                return self._json({"ok": True})
        self._json({"error": "not found"}, 404)


def main():
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    url = "http://127.0.0.1:%d/" % PORT
    print("记账本已启动: " + url + " (局域网 http://%s:%d/)" % (lan_ip(), PORT), flush=True)
    try:
        import webbrowser
        webbrowser.open(url)
    except Exception:
        pass
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
