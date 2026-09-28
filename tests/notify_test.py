# -*- coding: utf-8 -*-
import io, json, sys, urllib.request, urllib.error
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.path.insert(0, r"C:\Users\Administrator\Documents\Default Project\ledger")
import app

cases = [
    ("cmb sms",  "您账户6225于9月28日支出人民币35.50元，商户：美团，余额3000.00元", ("expense", 35.5, "美团")),
    ("wechat",   "微信支付：你于9月28日向「星巴克」付款33.00元", ("expense", 33.0, "星巴克")),
    ("ccb sms",  "您尾号1234的龙卡信用卡9月28日发生一笔支出交易88.00元，商户京东商城", ("expense", 88.0, "京东商城")),
    ("salary",   "您账户6225于9月28日收入人民币8000.00元，代发工资", ("income", 8000.0, "")),
    ("wechat in","微信支付到账通知：您收到转账20.00元", ("income", 20.0, "")),
    ("boc sms",  "您尾号8888账户9月28日支出12.50元，商户全家便利店", ("expense", 12.5, "全家便利店")),
    ("skip code","【银行】验证码123456，5分钟内有效", None),
    ("skip topup","零钱充值50.00元", None),
    ("skip bal", "您账户余额为1234.56元", None),
    ("meituan",  "【美团】支付成功，实付23.80元，已优惠2.00元", ("expense", 23.8, "")),
    ("12306",    "购票成功，G1234次列车，票价553.00元", ("expense", 553.0, "")),
]
fails = 0
for name, text, want in cases:
    got = app.parse_payment(text)
    if got != want:
        print("FAIL %s\n  want=%s\n  got =%s" % (name, want, got)); fails += 1
    else:
        print("PASS %s -> %s" % (name, got))
if fails:
    sys.exit(1)

# category guesses
assert app.guess_category("美团外卖", "美团") == "餐饮"
assert app.guess_category("", "京东商城") == "购物"
assert app.guess_category("电费", "") == "居住"
assert app.guess_income_cat("代发工资") == "工资"
print("PASS category guesses")

# e2e via HTTP
def post(path, body):
    r = urllib.request.Request("http://127.0.0.1:5100"+path, data=json.dumps(body).encode(),
                               headers={"Content-Type": "application/json"}, method="POST")
    try:
        return json.load(urllib.request.urlopen(r))
    except urllib.error.HTTPError as e:
        return json.load(e)

def put(path, body):
    r = urllib.request.Request("http://127.0.0.1:5100"+path, data=json.dumps(body).encode(),
                               headers={"Content-Type": "application/json"}, method="PUT")
    return json.load(urllib.request.urlopen(r))

for aid, tail in (("cmb", "6225"), ("ccb", "1234"), ("boc", "8888")):
    put("/api/account/" + aid, {"tail": tail})
print("PASS tails configured")

r = post("/api/notify", {"kind": "notify", "pkg": "com.chinapay.cmb", "title": "交易提醒",
                         "text": "您账户6225于9月28日支出人民币35.50元，商户：美团，余额3000.00元"})
assert r.get("action") == "inserted", r
tx = r["transaction"]
assert tx["account"] == "cmb" and tx["category"] == "餐饮" and tx["amount"] == 35.5 and tx["type"] == "expense", tx
print("PASS notify insert cmb/餐饮")

def put(path, body):
    r = urllib.request.Request("http://127.0.0.1:5100"+path, data=json.dumps(body).encode(),
                               headers={"Content-Type": "application/json"}, method="PUT")
    return json.load(urllib.request.urlopen(r))

for aid, tail in (("cmb", "6225"), ("ccb", "1234"), ("boc", "8888")):
    put("/api/account/" + aid, {"tail": tail})
print("PASS tails configured")

r = post("/api/notify", {"kind": "notify", "pkg": "com.chinapay.cmb", "title": "交易提醒",
                         "text": "您账户6225于9月28日支出人民币35.50元，商户：美团，余额3000.00元"})
assert r.get("action") == "duplicate", r
print("PASS dedup within 5min")

r = post("/api/notify", {"kind": "notify", "pkg": "com.tencent.mm", "title": "微信支付",
                         "text": "微信支付：你于9月28日向「星巴克」付款33.00元"})
assert r.get("action") == "inserted" and r["transaction"]["account"] == "boc", r
print("PASS wechat default account boc")

r = post("/api/notify", {"kind": "notify", "pkg": "com.android.mms", "title": "短信",
                         "text": "您账户6225于9月28日收入人民币8000.00元，代发工资"})
assert r.get("action") == "inserted" and r["transaction"]["type"] == "income" \
    and r["transaction"]["category"] == "工资", r
print("PASS salary income/工资")

r = post("/api/notify", {"kind": "screen", "pkg": "com.eg.android.AlipayGphone", "title": "",
                         "text": "支付宝-付款成功 45.60 元 商户: 滴滴出行"})
assert r.get("action") == "inserted", r
t2 = r["transaction"]
assert t2["amount"] == 45.6 and t2["category"] == "交通", t2
print("PASS screen alipay/交通")

r = post("/api/notify", {"kind": "notify", "pkg": "x", "title": "", "text": "验证码 8899"})
assert r.get("action") == "skipped", r
print("PASS skip noise")

st = json.load(urllib.request.urlopen("http://127.0.0.1:5100/api/state?month=2026-09"))
bal = {a["id"]: a["balance"] for a in st["accounts"]}
assert bal["cmb"] == 7964.5, bal
assert bal["boc"] == -(33 + 45.6), bal
assert st["summary"]["expense"] == 35.5 + 33 + 45.6, st["summary"]["expense"]
assert st["summary"]["income"] == 8000, st["summary"]["income"]
print("PASS balances/summary:", bal)
log = open(r"C:\Users\Administrator\Documents\Default Project\ledger\data\notify_log.jsonl", encoding="utf-8").read()
assert "inserted" in log and "duplicate" in log
print("PASS notify log written")
print("ALL PASS")
