#!/usr/bin/env python3
"""Screenshots of the site at a real viewport size, scrolled to each section and settled (Chrome DevTools protocol, standard library only).

    python3 -I scripts/site/shoot.py <url> <width> <height> <scale> <out prefix> [section ids...]

Example: shoot.py http://127.0.0.1:8765/ 390 844 2 out/en-390 top parts voice tracks remote install faq
"top" is scroll 0; "id+N" scrolls N px further down that section. Needs Google Chrome; serve the folder first (python3 -m http.server 8765 --directory docs).
"""
import base64, hashlib, json, os, socket, struct, subprocess, sys, tempfile, time, urllib.request

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
url, W, H, DSF, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
targets = sys.argv[6:] or ["top"]
PORT = 9333 + (os.getpid() % 500)

prof = tempfile.mkdtemp()
chrome = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--remote-debugging-port={PORT}", f"--user-data-dir={prof}", "about:blank"],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

class WS:
    def __init__(self, host, port, path):
        self.s = socket.create_connection((host, port))
        key = base64.b64encode(os.urandom(16)).decode()
        self.s.sendall((f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        buf = b""
        while b"\r\n\r\n" not in buf: buf += self.s.recv(1)
        assert b" 101 " in buf.split(b"\r\n")[0], buf
        self.n = 0
    def send(self, obj):
        data = json.dumps(obj).encode()
        hdr = bytearray([0x81])
        ln = len(data)
        if ln < 126: hdr.append(0x80 | ln)
        elif ln < 65536: hdr += bytes([0x80 | 126]) + struct.pack(">H", ln)
        else: hdr += bytes([0x80 | 127]) + struct.pack(">Q", ln)
        mask = os.urandom(4)
        hdr += mask
        self.s.sendall(bytes(hdr) + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))
    def recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.s.recv(n - len(buf))
            if not chunk: raise EOFError
            buf += chunk
        return buf
    def recv(self):
        msg = b""
        while True:
            b0, b1 = self.recv_exact(2)
            op, ln = b0 & 0x0F, b1 & 0x7F
            if ln == 126: ln = struct.unpack(">H", self.recv_exact(2))[0]
            elif ln == 127: ln = struct.unpack(">Q", self.recv_exact(8))[0]
            payload = self.recv_exact(ln)
            if op == 8: raise EOFError
            if op in (1, 0): msg += payload
            if b0 & 0x80 and op in (1, 0): return json.loads(msg)
    def call(self, method, params=None):
        self.n += 1
        mid = self.n
        self.send({"id": mid, "method": method, "params": params or {}})
        while True:
            r = self.recv()
            if r.get("id") == mid:
                if "error" in r: raise RuntimeError(r["error"])
                return r.get("result", {})

try:
    for _ in range(50):
        try:
            tabs = json.load(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json"))
            break
        except Exception: time.sleep(0.2)
    page = next(t for t in tabs if t["type"] == "page")
    ws = WS("127.0.0.1", PORT, page["webSocketDebuggerUrl"].split(str(PORT))[1])
    ws.call("Page.enable")
    ws.call("Emulation.setDeviceMetricsOverride", {"width": W, "height": H, "deviceScaleFactor": DSF, "mobile": W < 700})
    ws.call("Page.navigate", {"url": url})
    def ev(js):
        return ws.call("Runtime.evaluate", {"expression": js, "awaitPromise": True, "returnByValue": True}).get("result", {}).get("value")
    for _ in range(60):
        if ev("document.readyState") == "complete": break
        time.sleep(0.2)
    ev("document.documentElement.style.scrollBehavior='auto'")
    for name in targets:
        base, _, off = name.partition("+")        # "sessions+900" = 900 px below the start of that section
        off = int(off or 0)
        if base == "top": ev(f"window.scrollTo(0,{off})")
        else: ev(f"(()=>{{const s=document.getElementById('{base}'); window.scrollTo(0, s.getBoundingClientRect().top + scrollY - 56 + {off})}})()")
        time.sleep(0.4)
        # lazy images near the viewport finish loading, then the page settles (cross-fades, flag positions)
        ev("Promise.all([...document.images].filter(i=>{const r=i.getBoundingClientRect();return r.bottom>-200&&r.top<innerHeight+200}).map(i=>i.complete?1:new Promise(r=>{i.onload=r;i.onerror=r})))")
        time.sleep(0.9)
        shot = ws.call("Page.captureScreenshot", {"format": "png"})
        path = f"{out}-{name}.png"
        open(path, "wb").write(base64.b64decode(shot["data"]))
        print("wrote", path, "scrollY", ev("Math.round(scrollY)"))
finally:
    chrome.terminate()
