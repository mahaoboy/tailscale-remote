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

所有命令在仓库根目录执行（`server.py` / `remotectl.py` 所在目录）。若 `python3` 缺 `websockets`，用项目虚拟环境的解释器（例如 `/tmp/tr-venv/bin/python3`）。

```bash
python3 remotectl.py check
```

期望：

```
ws:on a11y:on shizuku:on backend:shizuku ver:1.6 safety:safe boot:73 up:13m autorecover:done recstep:-
```

需要的最低能力：

- `a11y:on` —— 没有它，点击/滑动/读屏/截图全部不可用
- `shizuku:on backend:shizuku` —— 只有 shell 类命令需要；纯 UI 操作可以没有
- `safety:safe` —— 安全模式，符合默认预期

`ping` 返回 `pong a11y:on shell:shizuku`（简版）。

### 通道不通时

`timeout after 20s` 说明中继没收到回复，按顺序检查：

1. 电脑上 `server.py` 是否在跑、日志里是否有 `[+] Phone connected`
2. 手机 APP 是否显示「已连接」；手机断线后每 5s 自动重连
3. Tailscale 两端是否在线（`tailscale status`）
4. 中继重启过仍不通：再跑一次 `ping`；还不行就说明手机侧 APP 被杀或 Tailscale 断开，如实告知用户，不要连续重试刷屏

不要为了"修好"去改手机系统设置或授权。

## 标准操作流程：看 → 动 → 复核

1. **看**：`gettext`（所有可见文字）或 `dump`（节点树）或 `screencap`（截图）
2. **动**：优先按文字操作 --- `taptext "确认"`；需要精确位置时先 `findtext "确认"` 拿坐标再 `tap x y`
3. **复核**：再 `gettext` 或截图确认界面确实变了；不要仅凭"命令返回 ok"就当操作成功
4. **收尾**：完成用户要求后，`back` / `home` 回到桌面，别把手机留在半开的设置页

截图会保存到 `/tmp/screenshot.png`，用 Read 工具查看该文件。

## 常用命令

| 命令 | 说明 |
|------|------|
| `check` | 完整状态：ws / a11y / shizuku / backend / 版本 / 安全模式 / 开机与自愈状态 |
| `screensize` | 返回 `宽x高`，用于判断坐标范围 |
| `gettext` | 当前窗口全部可见文字 |
| `dump` | 完整 UI 节点树（含 class / text / bounds） |
| `findtext "文字"` | 返回 `cx,cy [left,top,right,bottom]` |
| `taptext "文字"` | 点击包含该文字的元素，优先用它 |
| `tap x y` / `longtap x y` | 坐标点击 / 长按 |
| `swipe x1 y1 x2 y2 [毫秒]` | 滑动，默认 300ms |
| `type "文字"` | 写入当前焦点输入框（先 `tap` 输入框再输入） |
| `back` / `home` | 返回 / 回桌面 |
| `open <包名>` | 启动应用，如 `open com.android.mms` |
| `screenshot` | 无障碍截图（Android 11+），保存 `/tmp/screenshot.png` |
| `screencap` | shell 截图，需要 Shizuku |
| `input ...` / `keyevent <code>` | 仅 Shizuku：`input swipe 1 2 3 4 300`、`keyevent 3`(HOME)、`26`(电源) |
| `dumpsys battery` / `props ro.product.model` | 仅 Shizuku：查电量、机型 |
| `recover` | Shizuku 自愈，最多等 150s |
| `shizuku [status\|request]` | 查看/申请 Shizuku 授权 |

结果格式：`{"id":"...","ok":true,"output":"..."}` 或 `{"ok":false,"extra":"原因"}`。`extra` 是失败原因。

## 排障对照

| 返回/现象 | 处理 |
|-----------|------|
| `a11y service not connected` | 无障碍被系统关了。告知用户去 设置→无障碍 重新打开「远程控制」，不要自己改 |
| `no active window` | 屏幕关闭或无窗口。先 `keyevent 26`（需 Shizuku）或请用户亮屏解锁 |
| `text not found: X` | 文字不在当前屏。先 `gettext` 看实际文案（可能是简写/英文/图标按钮） |
| `no editable field focused` | 先点输入框，再 `type`；或改用 `input text`（Shizuku，仅 ASCII） |
| `performAction returned false` | 控件不支持直接操作，改用坐标 `tap` |
| shell 命令失败 / `shell:app` | Shizuku 未运行。跑 `recover`；仍失败就说明需要用户解锁并连 Wi-Fi 后在手机上启动 Shizuku |
| `recover` 返回 `Wi-Fi 未连接` / `锁屏` | 如实转告用户，等条件满足后重试 |
| 截图超时 | 当前界面禁止截图，换 `gettext`，或改用 `screencap`（需 Shizuku） |

## 已知限制

- 只能操作当前前台界面；无法在后台点按其他 App
- 锁屏无法解锁；无法读密码框内容
- 中文输入依赖 `ACTION_SET_TEXT`（`type`），`input text` 只支持 ASCII
- 手势类操作（`tap`/`swipe`）返回 ok 只代表手势已派发，界面是否变化要复核
- 中继无客户端认证，只应在 Tailscale 内网使用
- 一个中继同时只保留一个手机连接和一个客户端连接，新连接会替换旧连接

## 输出给用户

- 每完成一步给出可核对的结果（读到的文字、坐标、截图路径、状态串）
- 失败时给出具体 `extra` 原因与下一步，不要只说"失败了"
- 不要泄露内部细节（证书、内网 IP 可脱敏、中继实现），只讲用户关心的操作与结果
