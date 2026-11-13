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
import asyncio, base64, json, os, shlex, ssl, sys, time
import websockets

SERVER = "wss://127.0.0.1:8765/client"

SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

# 需要 shell(Shizuku) 才能成功执行的命令，用于提前提示
NEEDS_SHELL = {"screencap", "input", "keyevent", "grant", "revoke",
               "settings", "appops", "am", "dumpsys", "props", "install", "uninstall"}

TIMEOUTS = {
    "shell": 70, "am": 40, "dumpsys": 40, "settings": 30, "appops": 30,
    "screencap": 60, "screenshot": 60, "uis": 30, "uitap": 20,
    "install": 220, "update": 420, "put": 600, "uninstall": 90,
    "props": 20, "recover": 200,
}
DEFAULT_TIMEOUT = 20

HELP = """Commands:
  ping                          Check connection + a11y + shell backend
  check                         Full status (fg / built / hints)
  screensize                    Get screen resolution
  uis [filter]                  Compact list of operable nodes (#i class "text" [bounds] flags)
  uitap <i> [filter]            Tap node #i from the same list (same filter)
  tap x y                       Tap at coordinates (a11y)
  longtap x y                   Long press at coordinates (a11y)
  swipe x1 y1 x2 y2 [dur]       Swipe gesture (a11y, dur 50-3000ms)
  type text                     Set text into focused/first editable field (a11y, unicode ok)
  back / home                   Press back / home (a11y)
  findtext "text" [i] [--exact] Find text on screen, return coords (i-th match)
  taptext "text" [i] [--exact]  Tap i-th element containing text
  waittext "text" [sec]         Poll findtext until it appears
  gettext                       Read all visible text on screen
  dump                          Dump full UI node tree
  screenshot                    Screenshot (a11y, falls back to screencap)
  screencap                     Screenshot via shell, saves to /tmp/screenshot.png
  shell 'cmd'                   Execute shell command
  input <args...>               e.g. input tap 500 800 / input text 你好 (CJK auto-routes to a11y)
  keyevent <code>               e.g. keyevent 3 (HOME), 26 (POWER)
  grant <pkg> <perm>            pm grant
  revoke <pkg> <perm>           pm revoke
  settings <args...>            e.g. settings put global airplane_mode_on 0
  appops <args...>              e.g. appops set <pkg> MANAGE_EXTERNAL_STORAGE allow
  am <args...>                  e.g. am start -n com.example/.MainActivity
  dumpsys [args...]             e.g. dumpsys battery
  props [key]                   getprop, e.g. props ro.product.model
  put <local> [dest]            Push a local file (default dest /data/local/tmp/<name>)
  install <path>                pm install -r (auto-stages /sdcard to /data/local/tmp)
  uninstall <pkg> [--keep]      pm uninstall [-k]
  open <package>                Launch app by package name
  recover                       Self-heal Shizuku (Wi-Fi -> wireless debugging -> 启动)
  shizuku [request]             Shizuku status / request permission
  update [url]                  Download & install APK update
  quit                          Exit

Global flag:
  --timeout <sec>               Override wait time for this command
"""


def split_passthrough(args):
    """`am "start -n x"` 这种把整条命令塞进一个参数时，替用户拆成 token。"""
    if len(args) == 1 and any(c.isspace() for c in args[0]):
        try:
            return shlex.split(args[0])
        except ValueError:
            return args
    return args


def parse_text_args(args):
    exact = "--exact" in args
    rest = [a for a in args if a != "--exact"]
    index = 0
    if len(rest) > 1 and rest[-1].lstrip("-").isdigit():
        index = int(rest[-1])
        rest = rest[:-1]
    return " ".join(rest), exact, index


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
        text, exact, index = parse_text_args(args)
        obj["text"] = text
        obj["exact"] = exact
        obj["index"] = index
    elif cmd == "uis":
        obj["filter"] = " ".join(args)
    elif cmd == "uitap":
        obj["index"] = int(args[0])
        obj["filter"] = " ".join(args[1:])
    elif cmd == "shell":
        obj["command"] = " ".join(args)
    elif cmd == "update":
        obj["url"] = " ".join(args) if args else ""
    elif cmd == "open":
        obj["package"] = " ".join(args) if args else ""
    elif cmd == "input":
        obj["args"] = split_passthrough(args)
    elif cmd == "keyevent":
        obj["code"] = args[0]
    elif cmd in ("grant", "revoke"):
        obj["package"] = args[0]; obj["permission"] = args[1]
    elif cmd in ("settings", "appops", "am", "dumpsys"):
        obj["args"] = split_passthrough(args)
    elif cmd == "props":
        obj["key"] = args[0] if args else ""
    elif cmd == "put":
        local = os.path.expanduser(args[0])
        with open(local, "rb") as f:
            data = base64.b64encode(f.read()).decode()
        obj["data"] = data
        obj["name"] = os.path.basename(local)
        obj["path"] = args[1] if len(args) > 1 else ""
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


HINTS = [
    ("a11y service not connected", "无障碍没开：手机 设置→无障碍 启用「远程控制」"),
    ("no editable field focused", "先点一下输入框，再 type"),
    ("no active window", "屏幕可能关闭，先亮屏"),
    ("text not found", "先 gettext / uis 看实际文案（可能是简写或图标）"),
    ("index out of range", "重新跑一次 uis 取最新序号"),
    ("shell unavailable", "Shizuku 未运行：跑 recover"),
    ("shizuku off", "Shizuku 未运行：跑 recover"),
    ("backend=app", "该命令以应用身份执行会失败，需要 Shizuku"),
    ("timeout", "命令超时：加大 --timeout 或把命令拆小"),
    ("Unknown command", "shell 已执行，但命令本身不存在或参数被拆错"),
]


def print_hint(data, cmd=None):
    if not data or data.get("ok"):
        return
    blob = json.dumps(data, ensure_ascii=False)
    # 安装自己时本应用会被系统替换、连接中断，超时属预期而不是装失败
    if cmd in ("install", "update") and "timeout" in blob:
        print("  hint: 装的是本应用时连接会中断，超时属正常；等几秒用 check 看 ver/built 复核",
              file=sys.stderr, flush=True)
        return
    for needle, hint in HINTS:
        if needle in blob:
            print(f"  hint: {hint}", file=sys.stderr, flush=True)


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
        if data.get("id") != oid:
            continue
        # 长任务的中间进度：打印后继续等最终结果
        if "progress" in data and "ok" not in data:
            print(f"  .. {data['progress']}", file=sys.stderr, flush=True)
            continue
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


async def run_command(ws, cmd, args, timeout=None):
    wait = timeout if timeout is not None else TIMEOUTS.get(cmd, DEFAULT_TIMEOUT)
    data = await send_and_wait(ws, build(cmd, args), wait=wait)
    if cmd in ("screenshot", "screencap"):
        save_screenshot(data)
    else:
        print(json.dumps(data, ensure_ascii=False))
    print_hint(data, cmd)
    return data


async def wait_text(ws, text, seconds=15.0):
    deadline = time.monotonic() + seconds
    while True:
        data = await run_command(ws, "findtext", [text], timeout=max(3, min(10, seconds)))
        if data and data.get("ok"):
            return data
        if time.monotonic() >= deadline:
            print(json.dumps({"ok": False, "error": f"waittext timeout after {seconds}s"},
                             ensure_ascii=False))
            return data
        await asyncio.sleep(1.0)


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
            if cmd == "waittext":
                if not args:
                    print("  missing text")
                    continue
                sec = float(args[1]) if len(args) > 1 else 15.0
                await wait_text(ws, args[0], sec)
            else:
                await run_command(ws, cmd, args)
        except IndexError:
            print("  Missing arguments. Type 'help' for usage.")
        except ValueError:
            print("  Invalid number argument.")


def parse_global_flags(argv):
    timeout = None
    rest = []
    i = 0
    while i < len(argv):
        if argv[i] == "--timeout" and i + 1 < len(argv):
            try:
                timeout = float(argv[i + 1])
            except ValueError:
                print(f"  bad --timeout value: {argv[i + 1]}", file=sys.stderr)
                sys.exit(2)
            i += 2
        else:
            rest.append(argv[i])
            i += 1
    return timeout, rest


async def main():
    timeout, argv = parse_global_flags(sys.argv[1:])
    if argv and argv[0] != "interactive":
        cmd = argv[0]
        args = argv[1:]
        if cmd == "waittext" and not args:
            print("usage: waittext <text> [seconds]", file=sys.stderr)
            sys.exit(2)
        async with websockets.connect(SERVER, ssl=SSL_CTX, ping_interval=None, max_size=None) as ws:
            try:
                if cmd == "waittext":
                    sec = float(args[1]) if len(args) > 1 else 15.0
                    await wait_text(ws, args[0], sec)
                else:
                    await run_command(ws, cmd, args, timeout=timeout)
            except IndexError:
                print(f"  {cmd}: missing arguments. Type 'help' for usage.", file=sys.stderr)
                sys.exit(2)
            except ValueError:
                print("  Invalid number argument.", file=sys.stderr)
                sys.exit(2)
        return
    async with websockets.connect(SERVER, ssl=SSL_CTX, ping_interval=None, max_size=None) as ws:
        await interactive(ws)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
