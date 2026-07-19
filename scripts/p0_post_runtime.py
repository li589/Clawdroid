import json, time, urllib.request, re
BASE="http://127.0.0.1:8765/mcp"; TOKEN="p0-test-token-clawdroid-8765"; _id=0

def rpc(method, params=None):
    global _id; _id+=1
    req=urllib.request.Request(BASE,data=json.dumps({"jsonrpc":"2.0","id":_id,"method":method,"params":params or {}}).encode(),headers={"Authorization":f"Bearer {TOKEN}","Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=60) as r:
        return json.loads(r.read().decode())

def call(name, args):
    d=rpc("tools/call",{"name":name,"arguments":args})
    res=d.get("result") or {}
    text="\n".join(c.get("text","") for c in res.get("content") or [] if c.get("type")=="text")
    err=(res.get("_meta") or {}).get("error")
    print(f"{name}: isError={res.get('isError')} err={err}")
    print(" ", text[:300].replace("\n"," | "))
    return res, text

rpc("initialize",{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"p0-rt","version":"1"}})
call("get_capabilities",{})
call("app_launch",{"package":"com.android.settings"})
time.sleep(2)
call("app_stop",{"package":"com.android.settings"})
call("subscribe_events",{"operation":"start"})
time.sleep(1)
call("subscribe_events",{"operation":"stop"})
call("assist_ping",{})
