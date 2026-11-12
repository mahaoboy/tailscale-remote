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
"""
import asyncio, json, sys, time
import websockets

SERVER = "wss://127.0.0.1:8765/client"

import ssl
SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

HELP = """Commands:
  ping                          Check connection + a11y status
  check                        Full status check
  screensize                    Get screen resolution
  tap x y                       Tap at coordinates
  longtap x y                   Long press at coordinates
  swipe x1 y1 x2 y2 [dur]       Swipe gesture
  type text                     Type text into focused field
  back                          Press back
  home                          Press home
  findtext "text"               Find text on screen, return coords
  taptext "text"                Tap on element containing text
  gettext                       Read all visible text on screen
  dump                          Dump UI node tree
  screenshot                    Take screenshot (saves to /tmp/screenshot.png)
  shell 'cmd'                   Execute shell command
  open <package>                Launch app by package name
  update [url]                  Download & install APK update
  quit                          Exit
"""

async def send_and_wait(ws, cmd_obj, wait=10):
    cmd_obj["id"] = str(int(time.time() * 1000))
    await ws.send(json.dumps(cmd_obj))
    try:
        reply = await asyncio.wait_for(ws.recv(), timeout=wait)
        return json.loads(reply)
    except asyncio.TimeoutError:
        return {"ok": False, "error": "timeout"}


async def do_screenshot(ws, id):
    obj = {"cmd": "screenshot", "id": id}
    await ws.send(json.dumps(obj))
    reply = await asyncio.wait_for(ws.recv(), timeout=20)
    data = json.loads(reply)
    if data.get("ok") and "output" in data:
        img_b64 = data["output"].strip()
        out = "/tmp/screenshot.png"
        with open(out, "wb") as f:
            f.write(base64.b64decode(img_b64))
        print(f"[+] Screenshot saved to {out}")
    else:
        print(f"[-] Screenshot failed: {data}")


async def do(cmd, args):
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
    elif cmd == "screenshot":
        pass  # handled separately
    elif cmd in ("back", "home", "ping", "check", "screensize", "gettext", "dump"):
        pass  # no args needed

    async with websockets.connect(SERVER, ssl=SSL_CTX, ping_interval=None) as ws:
        if cmd == "screenshot":
            await do_screenshot(ws, str(int(time.time() * 1000)))
            return
        reply = await send_and_wait(ws, obj)
        print(json.dumps(reply, ensure_ascii=False))


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

        parts = line.split()
        cmd = parts[0]
        args = parts[1:]
        obj = {"cmd": cmd, "id": str(int(time.time() * 1000))}

        if cmd in ("tap", "longtap") and len(args) >= 2:
            obj["x"] = int(args[0]); obj["y"] = int(args[1])
            reply = await send_and_wait(ws, obj)
            print(f"  {json.dumps(reply, ensure_ascii=False)}")
        elif cmd == "swipe" and len(args) >= 2:
            obj["x1"] = int(args[0]); obj["y1"] = int(args[1])
            obj["x2"] = int(args[2]) if len(args) > 2 else obj["x1"]
            obj["y2"] = int(args[3]) if len(args) > 3 else obj["y1"]
            obj["duration"] = int(args[4]) if len(args) > 4 else 300
            reply = await send_and_wait(ws, obj)
            print(f"  {json.dumps(reply, ensure_ascii=False)}")
        elif cmd == "type" and args:
            obj["text"] = " ".join(args)
            reply = await send_and_wait(ws, obj)
            print(f"  {json.dumps(reply, ensure_ascii=False)}")
        elif cmd in ("findtext", "taptext") and args:
            obj["text"] = " ".join(args)
            reply = await send_and_wait(ws, obj)
            print(f"  {json.dumps(reply, ensure_ascii=False)}")
        elif cmd == "shell" and args:
            obj["command"] = " ".join(args)
            reply = await send_and_wait(ws, obj, wait=30)
            print(f"  {json.dumps(reply, ensure_ascii=False)}")
        elif cmd == "update":
            if args:
                obj["url"] = " ".join(args)
            reply = await send_and_wait(ws, obj, wait=60)
            print(f"  {json.dumps(reply, ensure_ascii=False)}")
        elif cmd == "open" and args:
            obj["package"] = " ".join(args)
            reply = await send_and_wait(ws, obj)
            print(f"  {json.dumps(reply, ensure_ascii=False)}")
        elif cmd == "screenshot":
            await do_screenshot(ws, obj["id"])
        elif cmd in ("back", "home", "ping", "check", "screensize", "gettext", "dump"):
            reply = await send_and_wait(ws, obj)
            print(f"  {json.dumps(reply, ensure_ascii=False)}")
        else:
            print("  Unknown command. Type 'help' for available commands.")


async def main():
    if len(sys.argv) > 1 and sys.argv[1] != "interactive":
        cmd = sys.argv[1]
        args = sys.argv[2:]
        await do(cmd, args)
        return
    async with websockets.connect(SERVER, ssl=SSL_CTX, ping_interval=None) as ws:
        await interactive(ws)


if __name__ == "__main__":
    import base64
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
