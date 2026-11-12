#!/usr/bin/env python3
"""
Remote control CLI — send commands to the phone over WebSocket.
Usage:
  python3 remotectl.py                    # interactive
  python3 remotectl.py ping
  python3 remotectl.py check
  python3 remotectl.py screensize
  python3 remotectl.py tap 500 800
  python3 remotectl.py longtap 500 800
  python3 remotectl.py swipe 500 800 500 400 300
  python3 remotectl.py type "hello"
  python3 remotectl.py back
  python3 remotectl.py home
  python3 remotectl.py findtext "东方财富"
  python3 remotectl.py taptext "东方财富"
  python3 remotectl.py gettext
  python3 remotectl.py dump
  python3 remotectl.py screenshot
  python3 remotectl.py shell 'wm size'
  python3 remotectl.py screencap
  python3 remotectl.py input tap 500 800
  python3 remotectl.py keyevent 3
  python3 remotectl.py grant com.example.app android.permission.CAMERA
  python3 remotectl.py settings put global airplane_mode_on 0
  python3 remotectl.py appops set com.example.app MANAGE_EXTERNAL_STORAGE allow
  python3 remotectl.py am start -n com.example/.MainActivity
  python3 remotectl.py dumpsys battery
  python3 remotectl.py props ro.product.model
"""
import asyncio, base64, json, shlex, ssl, sys, time
import websockets

SERVER = "wss://127.0.0.1:8765/client"

SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

# 需要 shell(Shizuku) 才能成功执行的命令，用于提前提示
NEEDS_SHELL = {"screencap", "input", "keyevent", "grant", "revoke",
               "settings", "appops", "am", "dumpsys", "props", "install", "uninstall"}

TIMEOUTS = {
    "shell": 40, "am": 40, "dumpsys": 40, "screencap": 60, "screenshot": 60,
    "install": 200, "update": 200, "uninstall": 90, "props": 20, "recover": 200,
}
DEFAULT_TIMEOUT = 20

HELP = """Commands:
  ping                          Check connection + a11y + shell backend
  check                         Full status check
  screensize                    Get screen resolution
  tap x y                       Tap at coordinates (a11y)
  longtap x y                   Long press at coordinates (a11y)
  swipe x1 y1 x2 y2 [dur]       Swipe gesture (a11y)
  type text                     Type text into focused field (a11y)
  back / home                   Press back / home (a11y)
  findtext "text"               Find text on screen, return coords
  taptext "text"                Tap on element containing text
  gettext                       Read all visible text on screen
  dump                          Dump UI node tree
  screenshot                    Screenshot (a11y, falls back to screencap)
  screencap                     Screenshot via shell, saves to /tmp/screenshot.png
  shell 'cmd'                   Execute shell command
  input <args...>               e.g. input tap 500 800 / input swipe 1 2 3 4 300
  keyevent <code>               e.g. keyevent 3 (HOME), 26 (POWER)
  grant <pkg> <perm>            pm grant
  revoke <pkg> <perm>           pm revoke
  settings <args...>            e.g. settings put global airplane_mode_on 0
  appops <args...>              e.g. appops set <pkg> MANAGE_EXTERNAL_STORAGE allow
  am <args...>                  e.g. am start -n com.example/.MainActivity
  dumpsys [args...]             e.g. dumpsys battery
  props [key]                   getprop, e.g. props ro.product.model
  install <path>                pm install -r <device path>
  uninstall <pkg> [--keep]      pm uninstall [-k]
  open <package>                Launch app by package name
  recover                       Self-heal Shizuku (Wi-Fi -> wireless debugging -> 启动)
  shizuku [request]             Shizuku status / request permission
  update [url]                  Download & install APK update
  quit                          Exit
"""


def build(cmd, args):
    obj = {"cmd": cmd}
    if cmd in ("tap", "longtap"):
        obj["x"] = int(args[0]); obj["y"] = int(args[1])
    elif cmd == "swipe":
        obj["x1"] = int(args[0]); obj["y1"] = int(args[1])
        obj["x2"] = int(args[2]) if len(args) > 2 else obj["x1"]
        obj["y2"] = int(args[3]) if len(args) > 3 else obj["y1"]
        obj["duration"] = int(args[4]) if len(args) > 4 else 300
    elif cmd == "type":
        obj["text"] = " ".join(args)
    elif cmd in ("findtext", "taptext"):
        obj["text"] = " ".join(args)
    elif cmd == "shell":
        obj["command"] = " ".join(args)
    elif cmd == "update":
        obj["url"] = " ".join(args) if args else ""
    elif cmd == "open":
        obj["package"] = " ".join(args) if args else ""
    elif cmd == "input":
        obj["args"] = args
    elif cmd == "keyevent":
        obj["code"] = args[0]
    elif cmd in ("grant", "revoke"):
        obj["package"] = args[0]; obj["permission"] = args[1]
    elif cmd in ("settings", "appops", "am", "dumpsys"):
        obj["args"] = args
    elif cmd == "props":
        obj["key"] = args[0] if args else ""
    elif cmd == "install":
        obj["path"] = args[0]
    elif cmd == "uninstall":
        keep = "--keep" in args
        rest = [a for a in args if a != "--keep"]
        obj["package"] = rest[0]
        obj["keepData"] = keep
    elif cmd == "shizuku":
        obj["action"] = args[0] if args else "status"
    return obj


async def send_and_wait(ws, obj, wait=DEFAULT_TIMEOUT):
    oid = str(int(time.time() * 1000))
    obj["id"] = oid
    await ws.send(json.dumps(obj))
    deadline = time.monotonic() + wait
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return {"ok": False, "error": f"timeout after {wait}s"}
        try:
            reply = await asyncio.wait_for(ws.recv(), timeout=remaining)
        except asyncio.TimeoutError:
            return {"ok": False, "error": f"timeout after {wait}s"}
        try:
            data = json.loads(reply)
        except json.JSONDecodeError:
            continue
        # 忽略不属于本次请求的消息（例如上一次超时后才到的返回）
        if data.get("id") == oid:
            return data


def save_screenshot(data):
    if data.get("ok") and data.get("output"):
        img_b64 = data["output"].strip()
        out = "/tmp/screenshot.png"
        with open(out, "wb") as f:
            f.write(base64.b64decode(img_b64))
        print(f"[+] Screenshot saved to {out}  ({len(img_b64) * 3 // 4} bytes, {data.get('extra', '')})")
    else:
        print(f"[-] Screenshot failed: {json.dumps(data, ensure_ascii=False)}")


async def run_command(ws, cmd, args):
    wait = TIMEOUTS.get(cmd, DEFAULT_TIMEOUT)
    data = await send_and_wait(ws, build(cmd, args), wait=wait)
    if cmd in ("screenshot", "screencap"):
        save_screenshot(data)
    else:
        print(json.dumps(data, ensure_ascii=False))


async def interactive(ws):
    print(HELP)
    while True:
        try:
            line = input("[remote] > ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if line in ("quit", "exit", "q"):
            break
        if line in ("help", "?"):
            print(HELP)
            continue
        if not line:
            continue

        try:
            parts = shlex.split(line)
        except ValueError as e:
            print(f"  parse error: {e}")
            continue
        cmd, args = parts[0], parts[1:]

        try:
            await run_command(ws, cmd, args)
        except IndexError:
            print("  Missing arguments. Type 'help' for usage.")
        except ValueError:
            print("  Invalid number argument.")


async def main():
    if len(sys.argv) > 1 and sys.argv[1] != "interactive":
        cmd = sys.argv[1]
        args = sys.argv[2:]
        async with websockets.connect(SERVER, ssl=SSL_CTX, ping_interval=None, max_size=None) as ws:
            await run_command(ws, cmd, args)
        return
    async with websockets.connect(SERVER, ssl=SSL_CTX, ping_interval=None, max_size=None) as ws:
        await interactive(ws)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
