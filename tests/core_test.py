import json, urllib.request, urllib.error, sys
BASE="http://127.0.0.1:5100"
def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE+path, data=data, method=method, headers={"Content-Type":"application/json"})
    try: return urllib.request.urlopen(r).read().decode(), 200
    except urllib.error.HTTPError as e: return e.read().decode(), e.code
def must(cond, msg):
    print(("PASS " if cond else "FAIL ")+msg)
    if not cond: sys.exit(1)
raw = urllib.request.urlopen(BASE+"/").read().decode()
must("记账本" in raw, "index served")
_,c = req("POST","/api/transaction",{"type":"income","amount":8000,"date":"2026-09-10","account":"cmb","category":"工资","note":"pay"})
must(c==200, "income add")
_,c = req("POST","/api/transaction",{"type":"expense","amount":35.5,"date":"2026-09-12","account":"boc","category":"餐饮","note":"lunch"})
must(c==200, "expense add")
_,c = req("POST","/api/transaction",{"type":"transfer","amount":2000,"date":"2026-09-11","account":"cmb","to":"boc"})
must(c==200, "transfer add")
for payload,label in [({"type":"expense","amount":-1,"date":"2026-09-12","account":"boc","category":"餐饮"},"neg"),
                      ({"type":"transfer","amount":10,"date":"2026-09-12","account":"boc","to":"boc"},"self"),
                      ({"type":"expense","amount":10,"date":"2026-09-12","account":"boc"},"nocat"),
                      ({"type":"expense","amount":10,"date":"2026-09-31","account":"boc","category":"餐饮"},"baddate")]:
    _,c = req("POST","/api/transaction",payload); must(c==400, "reject "+label)
_,c = req("POST","/api/budget",{"category":"餐饮","limit":1000}); must(c==200, "budget set")
s = json.loads(req("GET","/api/state?month=2026-09")[0])
must(s["summary"]["income"]==8000, "summary income")
must(s["summary"]["expense"]==35.5, "summary expense")
bal = {a["id"]:a["balance"] for a in s["accounts"]}
must(bal["cmb"]==6000.0, "cmb 6000 = "+str(bal["cmb"]))
must(bal["boc"]==1964.5, "boc 1964.5 = "+str(bal["boc"]))
must(bal["ccb"]==0.0, "ccb 0")
cat = [c for c in s["summary"]["categories"] if c["category"]=="餐饮"]
must(cat and 3.5 <= cat[0]["pct"] <= 3.7 and cat[0]["limit"]==1000, "budget pct "+str(cat))
txid = s["transactions"][0]["id"]
_,c = req("PUT","/api/transaction/"+txid,{"type":"expense","amount":50,"date":"2026-09-13","account":"boc","category":"餐饮","note":"edit"})
must(c==200, "tx edit")
s2 = json.loads(req("GET","/api/state?month=2026-09")[0])
must(s2["summary"]["expense"]==50.0, "expense after edit")
_,c = req("POST","/api/account",{"name":"abc","initial":100}); must(c==200, "account add")
newid = json.loads(req("GET","/api/state?month=2026-09")[0])["accounts"][-1]["id"]
_,c = req("PUT","/api/account/"+newid,{"name":"abc2","initial":150}); must(c==200, "account edit")
s3 = json.loads(req("GET","/api/state?month=2026-09")[0])
must(any(a["id"]==newid and a["balance"]==150 for a in s3["accounts"]), "account balance edit")
_,c = req("DELETE","/api/account/"+newid); must(c==200, "account delete")
_,c = req("DELETE","/api/transaction/"+txid); must(c==200, "tx delete")
s4 = json.loads(req("GET","/api/state?month=2026-01")[0])
must(s4["summary"]["expense"]==0, "other month empty")
d = json.load(open(r"C:\Users\Administrator\Documents\Default Project\ledger\data\ledger.json", encoding="utf-8"))
must(len(d["transactions"])==2, "persisted count=2")
print("ALL PASS")
