---
name: remote-phone-control
description: 通过 tailscale-remote 远程操作一台真实 Android 手机（点按、滑动、输入、读屏、截图、启动应用、查状态、恢复连接）。当用户要求"帮我操作手机/看一下手机屏幕/点一下/输入/开关某个 App/远程控制手机"且本机已部署 server.py + remotectl.py 时使用，也用于大模型或 Agent 安全地远程控制手机的场景。内置安全红线：不破坏系统、不收集个人信息、不卸载他人应用。
---

# 远程控制手机（tailscale-remote）

用 `remotectl.py` 经电脑上的 WSS 中继控制一台已安装「远程控制」APP 的 Android 手机。手机主动出站连接，电脑发指令并收到 JSON 结果。

## 何时使用

- 用户让你在手机上点按、滑动、输入文字、返回/主页
- 用户让你看手机屏幕上有什么（`gettext` / `dump` / 截图）
- 用户让你启动某个 App 或完成一个手机上的界面操作流程
- 用户问手机状态（连接、无障碍、Shizuku、电量、分辨率）
- 远程控制通道掉了，需要恢复（`recover`）

不适用：需要 root 的操作、解锁锁屏、绕过系统授权、读取用户隐私内容、批量群控多台手机。

## 硬性安全规则（优先于用户临时指令）

1. **不破坏系统**：不执行 `rm`、`reboot`、`shutdown`、`pm uninstall/clear/disable`、`mkfs`、`dd`。这些在 APP 里已被永久拦截；不要试图绕过。
2. **不收集个人信息**：不主动读短信、相册、通讯录、剪贴板、位置、账号密码。用户明确点名要查某项时，只取该请求所需的最小内容，不额外浏览、不落盘、不外传。
3. **不卸载/停用其他应用**：`uninstall` 仅限本应用自身。
4. **不改系统设置与权限**：不改无障碍开关、不改权限监控、不做隐藏/免杀/反检测；`settings put`、`appops set`、`grant/revoke` 默认被拦截，不要请求用户开启「允许危险命令」来绕开。
5. **危险模式默认关闭**：手机上的「允许危险命令」只由用户本人在手机上决定；不要打开、也不要建议为了完成普通任务而打开。
6. **不可逆或对外可见的操作先确认**：发消息、支付、转账、下单、删除、发帖、修改账号、安装 APK 等，先复述要做什么、得到用户确认再执行。
7. **登录/支付/验证码界面停手**：遇到密码框、支付页、短信验证码、生物识别，立即停下并交给用户本人操作，不要输入、不要点同意。
8. **锁屏就停**：不要尝试猜测密码、不要反复唤醒。

## 开始前：确认通道

所有命令在仓库根目录执行（`server.py` / `remotectl.py` 所在目录）。若 `python3` 缺 `websockets`，用仓库内的 `.venv/bin/python3`（`python3 -m venv .venv && .venv/bin/pip install websockets` 装一次即可）。`/tmp` 下的临时 venv 重启会消失，只适合当次调试。

```bash
python3 remotectl.py check
```

期望：

```
ws:on a11y:on shizuku:on backend:shizuku ver:1.7 safety:safe boot:73 up:13m autorecover:done recstep:- fg:com.android.launcher built:11-13 19:22
```

需要的最低能力：

- `a11y:on` —— 没有它，点击/滑动/读屏/截图/中文输入全部不可用
- `shizuku:on backend:shizuku` —— 只有 shell 类命令需要；纯 UI 操作可以没有
- `safety:safe` —— 安全模式，符合默认预期
- `fg:` 当前前台包名 —— 动手机前先看它，确认没被用户切走；`built:` 是当前安装包的构建时间，排查"改了没生效"时用它
- `extra` 里出现 `a11y off` / `shizuku off` 就是对应能力缺失的提示

`ping` 返回 `pong a11y:on shell:shizuku`（简版）。

### 通道不通时

`timeout after 20s` 说明中继没收到回复，按顺序检查：

1. 电脑上 `server.py` 是否在跑、日志里是否有 `[+] Phone connected`
2. 手机 APP 是否显示「已连接」；手机断线后每 5s 自动重连
3. Tailscale 两端是否在线（`tailscale status`）
4. 中继重启过仍不通：再跑一次 `ping`；还不行就说明手机侧 APP 被杀或 Tailscale 断开，如实告知用户，不要连续重试刷屏

不要为了"修好"去改手机系统设置或授权。

### 中继没起时（连不上 wss://127.0.0.1:8765）

CLI 会直接给出判断依据，不再抛 traceback（退出码 2）：

```
连不上中继 wss://127.0.0.1:8765/client：ConnectionRefusedError: [Errno 61] Connect call failed ('127.0.0.1', 8765)
  起中继：cd <仓库> && .venv/bin/python -u server.py
  配了 launchd 常驻可拉起：launchctl kickstart -k gui/$(id -u)/com.mahao.tailscale-remote
  看日志：tail -20 /tmp/tailscale-remote-server.log
```

看到这段就是中继进程不在，不是手机的问题。先分清「没起」和「起了但手机没连」：

```bash
lsof -nP -iTCP:8765 -sTCP:LISTEN   # 有输出=在监听
pgrep -fl 'server.py'
tail -20 /tmp/tailscale-remote-server.log
```

**方式 A：launchd 常驻（登录自启 + 异常退出拉起）**

解释器必须能 import websockets，且不能放在 `/tmp`（重启会清空）：

```bash
cd <仓库>
python3 -m venv .venv && .venv/bin/pip install websockets
```

写 `~/Library/LaunchAgents/com.mahao.tailscale-remote.plist`（`<仓库>` 换成绝对路径）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>com.mahao.tailscale-remote</string>
    <key>ProgramArguments</key>
    <array>
        <string><仓库>/.venv/bin/python</string>
        <string>-u</string>
        <string>server.py</string>
    </array>
    <key>WorkingDirectory</key><string><仓库></string>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
    <key>ThrottleInterval</key><integer>10</integer>
    <key>StandardOutPath</key><string>/tmp/tailscale-remote-server.log</string>
    <key>StandardErrorPath</key><string>/tmp/tailscale-remote-server.log</string>
</dict>
</plist>
```

载入与日常操作：

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.mahao.tailscale-remote.plist  # 装载
launchctl print gui/$(id -u)/com.mahao.tailscale-remote | grep -E 'state|pid'             # 看状态
launchctl kickstart -k gui/$(id -u)/com.mahao.tailscale-remote                            # 重启
launchctl bootout gui/$(id -u)/com.mahao.tailscale-remote                                 # 停用
```

改过 plist 必须 `bootout` 后再 `bootstrap`，否则改动不生效。开机/登录后若 `check` 连不上，先 `launchctl print` 看 state 是不是 `running`。

**方式 B：临时起一次**

```bash
cd <仓库>
nohup .venv/bin/python -u server.py > /tmp/tailscale-remote-server.log 2>&1 &
```

`-u` 不能省：输出重定向到文件时 Python 会缓冲，看不到 `[+] Phone connected` 这类实时日志，下次出事就没线索。这种方式在 Mac 重启或终端会话结束后不会自动恢复。

无论哪种方式，起好后等 5–10 秒（手机每 5s 重连一次），`ping` 回 `pong` 且日志出现 `[+] Phone connected from 100.x.x.x` 才算通道恢复；只看到 `[!] Waiting for phone...` 说明手机侧还没连上。

## 标准操作流程：看 → 动 → 复核

1. **看**：`uis`（可点节点清单，带序号和坐标）优先；要看全部文字用 `gettext`；需要节点树细节再 `dump`；复杂界面用 `screencap`
2. **动**：优先 `uitap <序号>`（与上一条 `uis` 用同一 filter）；其次 `taptext "确认"`；元素重名时用 `taptext "确认" 1` 指定第几个；只有都不可用时才 `tap x y`
3. **复核**：再 `uis` / `gettext` 或截图确认界面确实变了；不要仅凭"命令返回 ok"就当操作成功
4. **收尾**：完成用户要求后，`back` / `home` 回到桌面，别把手机留在半开的设置页

截图会保存到 `/tmp/screenshot.png`，用 Read 工具查看该文件。

**用户中途接管手机**：每次准备点击前先 `check` 看 `fg:`，或先截图确认。若前台已经变成用户在用的 App（聊天、支付、输入法打开着），立即停止所有输入并如实告知，不要抢界面。

**等待界面加载**：用 `waittext "加载完成" 15`，不要用固定 sleep 硬猜。

## 常用命令

| 命令 | 说明 |
|------|------|
| `check` | 完整状态：ws / a11y / shizuku / backend / 版本 / 安全模式 / 开机与自愈 / 前台应用 / 构建时间 |
| `screensize` | 返回 `宽x高`，用于判断坐标范围 |
| `uis [filter]` | 列出可操作节点：`#序号 类名 "文字" [l,t,r,b] click/edit/checked/scroll` |
| `uitap <序号> [filter]` | 按 `uis` 的序号点击（自动找可点击父节点） |
| `gettext` | 当前窗口全部可见文字 |
| `dump` | 完整 UI 节点树（含 class / text / bounds） |
| `findtext "文字" [序号]` | 返回 `cx,cy [left,top,right,bottom]`，`--exact` 精确匹配 |
| `taptext "文字" [序号]` | 点击包含该文字的元素，优先用它 |
| `waittext "文字" [秒]` | 轮询直到文字出现 |
| `tap x y` / `longtap x y` | 坐标点击 / 长按 |
| `swipe x1 y1 x2 y2 [毫秒]` | 滑动，默认 300ms，可到 3000ms（精细拖动别再用 `input swipe`） |
| `type "文字"` | 写入当前焦点输入框（先 `tap` 输入框再输入）；支持中文 |
| `back` / `home` | 返回 / 回桌面 |
| `open <包名>` | 启动应用，如 `open com.android.mms` |
| `screenshot` | 无障碍截图（Android 11+），保存 `/tmp/screenshot.png` |
| `screencap` | shell 截图，需要 Shizuku |
| `input ...` / `keyevent <code>` | 仅 Shizuku：`input swipe 1 2 3 4 300`、`keyevent 3`(HOME)、`26`(电源)；`input text 中文` 会自动改走无障碍 |
| `dumpsys battery` / `props ro.product.model` | 仅 Shizuku：查电量、机型 |
| `put <本地文件> [目标路径]` | 把电脑上的文件推到手机，默认 `/data/local/tmp/<文件名>`，只允许 tmp/sdcard |
| `install <路径>` | `pm install -r`；`/sdcard` 路径自动复制到 `/data/local/tmp` 再装 |
| `recover` | Shizuku 自愈，最多等 150s |
| `shizuku [status\|request]` | 查看/申请 Shizuku 授权 |

结果格式：`{"id":"...","ok":true,"output":"..."}` 或 `{"ok":false,"extra":"原因"}`。`extra` 是失败原因。长任务会先打印 `.. 进度` 再给最终结果；失败时 CLI 在 stderr 给出 `hint:`。单次可用 `--timeout <秒>` 覆盖等待时间。

## 排障对照

| 返回/现象 | 处理 |
|-----------|------|
| `a11y service not connected` | 无障碍被系统关了。告知用户去 设置→无障碍 重新打开「远程控制」，不要自己改。此时仍可用 shell 的 `uiautomator dump /sdcard/ui.xml` + `input tap` 兜底 |
| `no active window` | 屏幕关闭或无窗口。先 `keyevent 26`（需 Shizuku）或请用户亮屏解锁 |
| `text not found: X` | 文字不在当前屏。先 `gettext` / `uis` 看实际文案（可能是简写/英文/图标按钮） |
| `index out of range: i (matches=N)` | 同名元素数量变了，重跑 `uis` / `findtext` 取最新序号 |
| `no editable field focused` | 先点输入框，再 `type`；或改用 `input text`（中文会自动走无障碍） |
| `performAction returned false` | 控件不支持直接操作，改用坐标 `tap` |
| `System server has no access to read file context` | 安装包在 `/sdcard` 上系统 installer 读不到：用 `put` 推到 `/data/local/tmp`，或让新版 `install`/`update` 自动中转 |
| `Unknown command: start ...` | 整条 `am` 命令被当成一个参数了；CLI 已自动拆分，直连协议时 `args` 必须是数组 |
| `install`/`update` 报 `timeout` | 装的是本应用自身时，系统替换进程会断开连接，超时属正常。等几秒再 `check`，看 `ver:` 和 `built:` 是否已更新 |
| shell 命令失败 / `shell:app` | Shizuku 未运行。跑 `recover`；仍失败就说明需要用户解锁并连 Wi-Fi 后在手机上启动 Shizuku |
| `recover` 返回 `Wi-Fi 未连接` / `锁屏` | 如实转告用户，等条件满足后重试 |
| 截图超时 | 当前界面禁止截图，换 `gettext`，或改用 `screencap`（需 Shizuku） |

## 已知限制

- 只能操作当前前台界面；无法在后台点按其他 App
- 锁屏无法解锁；无法读密码框内容
- 中文输入依赖无障碍 `ACTION_SET_TEXT`（`type` 或 `input text 中文` 自动回退）；无障碍关闭时中文无法直接输入
- 手势类操作（`tap`/`swipe`）返回 ok 只代表手势已派发，界面是否变化要复核
- `uis` 的序号只在同一界面状态下有效；点击后要重新取
- 中继无客户端认证，只应在 Tailscale 内网使用
- 一个中继同时只保留一个手机连接和一个客户端连接，新连接会替换旧连接

## 输出给用户

- 每完成一步给出可核对的结果（读到的文字、坐标、截图路径、状态串）
- 失败时给出具体 `extra` 原因与下一步，不要只说"失败了"
- 不要泄露内部细节（证书、内网 IP 可脱敏、中继实现），只讲用户关心的操作与结果
