import json, urllib.request
B="http://127.0.0.1:8765/mcp"; T="p0-test-token-clawdroid-8765"; i=0
def rpc(m,p=None):
    global i; i+=1
    req=urllib.request.Request(B,data=json.dumps({"jsonrpc":"2.0","id":i,"method":m,"params":p or {}}).encode(),headers={"Authorization":"Bearer "+T,"Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=40) as r: return json.loads(r.read().decode())
def call(n,a):
    d=rpc("tools/call",{"name":n,"arguments":a}); res=d.get("result") or {}
    text="\n".join(c.get("text","") for c in res.get("content") or [] if c.get("type")=="text")
    print(n, "isError", res.get("isError"), (res.get("_meta") or {}).get("error"), "|", text[:220].replace("\n"," | "))
rpc("initialize",{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"1"}})
call("app_stop",{"package":"com.android.settings"})
call("subscribe_events",{"operation":"start"})
call("subscribe_events",{"operation":"stop"})
