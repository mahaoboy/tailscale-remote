# 使用说明书

用一台电脑（或一台常开的小主机）通过 Tailscale 内网，远程操作一台 Android 手机：点按、滑动、输入、读屏、截图、启动应用，以及在授权后执行 shell 命令。

适用于「大模型 / Agent 远程安全控制手机」的场景：手机只主动出站连接，不需要公网端口、不需要 root、不需要 USB 常连。典型用法是给 AI Agent 提供一套可控的终端入口，由 Agent 读屏、决策、点击并复核结果。

## 1. 它由哪几部分组成

| 组件 | 运行位置 | 作用 |
|------|----------|------|
| `server.py` | 电脑 | WSS 中继（默认 8765），转发指令与结果 |
| `remotectl.py` | 电脑 | 命令行客户端，发指令、收 JSON 结果 |
| `https_server.py` | 电脑 | 可选，HTTPS 下载页面（默认 8080，分发 APK） |
| `app-debug.apk` | 手机 | 前台服务 + 无障碍服务 + 可选 Shizuku 执行层 |

```
Agent / 你
   │  python3 remotectl.py <cmd>
   ▼
server.py (WSS :8765)  ←── Tailscale 内网 ──→  手机 APP (:8765/phone)
                                                   ├─ 无障碍：点按/滑动/输入/读屏/截图
                                                   └─ Shizuku（可选）：shell / pm / settings / screencap
```

## 2. 前置条件

- 手机：Android 8.0+（`minSdk 26`）。无障碍手势需要 Android 8+；用无障碍截屏需要 Android 11+。
- 电脑：Python 3.9+，能装 `websockets`；与手机在同一个 Tailscale 网络里。
- 手机和电脑都安装并登录 **同一个 Tailscale 账号**（或通过 ACL 互通的账号）。
- 手机需要能解锁使用；远程无法解锁锁屏，也不应尝试猜密码。

## 3. 电脑侧部署

### 3.1 安装 Tailscale 并取地址

```bash
# macOS: brew install tailscale（或从官网安装 App）
tailscale up
tailscale ip -4          # 记下这个 100.x.y.z 地址
```

### 3.2 准备代码与 Python 依赖

```bash
git clone https://github.com/mahaoboy/tailscale-remote.git
cd tailscale-remote
python3 -m pip install websockets
```

### 3.3 生成自签 TLS 证书（仅本机使用）

仓库不包含证书（`certs/` 已被忽略），首次使用需要自己生成一份。手机端 APP 是 debug 构建，会接受自签证书，所以自签即可：

```bash
mkdir -p certs
IP=$(tailscale ip -4)
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout certs/key.pem -out certs/cert.pem \
  -subj "/CN=tailscale-remote" \
  -addext "subjectAltName=IP:${IP}"
```

### 3.4 启动中继

```bash
python3 server.py                 # WSS :8765
python3 https_server.py 8080      # 可选：https://<tailscale-ip>:8080/ 分发 APK
```

看到 `[*] WebSocket server on 0.0.0.0:8765 (SSL/WSS)` 即启动成功。`https_server.py` 只在你需要通过浏览器给手机传 APK 时才需要。

> 中继本身不做客户端认证：能连到 8765 的人就能控制手机。务必只用 Tailscale 内网，并用 Tailscale ACL 限制可访问设备（见第 7 节）。

## 4. 手机侧安装

### 4.1 下载 APK

**方式 A（推荐）**：在手机浏览器打开 GitHub Release 页面下载最新 `app-debug.apk`：

```
https://github.com/mahaoboy/tailscale-remote/releases/latest
```

**方式 B**：用电脑的 HTTPS 分发服务，在手机浏览器打开：

```
https://<tailscale-ip>:8080/build/app-debug.apk
```

首次侧载需要允许浏览器/文件管理器「安装未知应用」。

APK 使用 Gradle 的 debug 签名。如果手机上装过签名不同的旧版本，先卸载再装（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。

### 4.2 开启无障碍

设置 → 无障碍 → 已下载的服务 → **远程控制** → 打开。

无障碍服务提供：点击、长按、滑动、输入文字、返回/主页、读屏、查找文字、截屏（Android 11+ 走无障碍截图）。

### 4.3 连接中继

打开「远程控制」APP：

1. 服务器地址填 `wss://<tailscale-ip>:8765/phone`
2. 点「连接」，状态变成「已连接」
3. 回到桌面即可，APP 以前台服务保持连接；重启后会自动重连

APP 首页还会显示无障碍状态、Shizuku 状态和日志，方便排查。

### 4.4（可选）启用 Shizuku

不启用也能完成绝大多数 UI 操作；启用后多出 `shell / pm / settings / appops / am / dumpsys / screencap` 等「adb 能力」，以 `shell`(uid 2000) 身份执行，不需要 root。

1. APP 首页点「安装并启用 Shizuku」，点「安装内置 Shizuku」调起系统安装器
2. 打开 Shizuku，选「通过无线调试启动」，按提示配对一次（这一步必须由本人在手机上操作）
3. 回到 APP 点「授权 Shizuku」，选允许
4. 状态行显示「Shizuku 已授权：shell 能力可用」即生效，`ping` 会返回 `shell:shizuku`

## 5. 开始使用

```bash
python3 remotectl.py            # 交互式
python3 remotectl.py ping
python3 remotectl.py check
```

常用命令：

| 目的 | 命令 |
|------|------|
| 连接与能力检查 | `ping` / `check` |
| 看屏幕文字 | `gettext` |
| 按文字点击 | `taptext "确定"` |
| 查文字坐标 | `findtext "设置"` |
| 按坐标点击 | `tap 540 1188` |
| 滑动 / 长按 | `swipe 500 1800 500 600 300` / `longtap 540 1188` |
| 输入文字 | `type "hello"` |
| 返回 / 主页 | `back` / `home` |
| 启动应用 | `open com.android.settings` |
| 截图 | `screenshot`（无障碍）或 `screencap`（shell），保存到 `/tmp/screenshot.png` |
| 屏幕分辨率 | `screensize` |
| Shizuku 自愈 | `recover` |
| 完整命令表 | 见 [README](../README.md#功能) |

返回统一是 JSON：

```json
{"id":"1735400000000","ok":true,"output":"..."}
{"id":"1735400000001","ok":false,"extra":"a11y service not connected"}
```

`output` 是结果，`extra` 是失败原因或附加信息。单条命令超时按类型区分（普通 20s，`shell/am/dumpsys` 40s，`screencap/screenshot` 60s，`install/update/recover` 200s）。

如果中继不在本机，改 `remotectl.py` 顶部的 `SERVER` 常量（默认 `wss://127.0.0.1:8765/client`）。

## 6. 重启 / 掉线后的恢复

- 手机重启后 APP 会通过 `BOOT_COMPLETED` 自动拉起前台服务并重连（以前打开过一次并保存过地址为前提）。
- 开机 15 分钟内首次连上且 Shizuku 未运行，会自动执行一次自愈：开 Wi-Fi → 开无线调试 → 打开 Shizuku 点「启动」→ 等就绪。最多 5 次，每轮间隔 60s，单次开机只跑一轮。
- 手动触发：`python3 remotectl.py recover`，最多等 150s。
- `check` 会给出 `boot:<次数> up:<开机分钟> autorecover:done|pending|skip recstep:<步骤>`。

自愈只做「打开开关 / 点按钮」，不写系统设置值。已知前提：

- 手机已解锁（锁屏时直接返回失败）
- 能连上可用 Wi-Fi（无线调试依赖 Wi-Fi，不会去猜密码）
- Shizuku 首次必须在手机上手动配对一次，之后才能自动启动
- 无障碍服务处于开启状态

## 7. 安全与隐私

**必须遵守的边界：**

- 不改系统、不收集个人信息、不卸载其他应用。
- 不使用本工具绕过授权、规避权限监控或隐藏痕迹。

**代码层的硬限制**（`SafetyPolicy`，远程无法修改）：

- 永久拦截：`rm`、`reboot/shutdown`、`pm uninstall`（本应用除外）、`pm clear/disable`、`mkfs/dd/wipe` 等
- 默认拦截：任意 `shell`、`grant/revoke`、修改 `settings/appops`、非更新包 `install`、`am` 的非启动子命令
- 需要解锁：上述操作只有在**手机上**勾选「允许危险命令」后才放行；开关不在远程可改范围内

**网络与隐私：**

- 指令全程走 Tailscale 内网 + WSS，不开放公网端口
- APP 只在本地保存服务器地址、开关状态和日志，不上传任何数据
- 中继没有客户端认证，请用 Tailscale ACL 把 8765 只开放给你的设备：

```json
{
  "acls": [
    { "action": "accept", "src": ["autogroup:self"], "dst": ["autogroup:self:8765"] }
  ]
}
```

## 8. 常见问题

| 现象 | 原因与处理 |
|------|-----------|
| `remotectl.py` 一直 `timeout` | 中继没起、手机没连、或 Tailscale 不通。看 `server.py` 是否打印 `[+] Phone connected`；手机 APP 里确认状态 |
| `ping` 返回 `a11y:off` | 无障碍被关闭（系统更新/省电策略会关）。重新到设置里打开 |
| `ping` 返回 `shell:app` | Shizuku 没运行或没授权，shell 类命令会失败；执行 `recover` 或按 4.4 手动启动 |
| 安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 旧包签名不同，先卸载旧版本再装；卸载后需重新授权 Shizuku |
| 重启后没自动连上 | 确认装完后至少手动连接过一次（地址已保存）、APP 未被系统限制后台；一加/ColorOS 需允许自启动与后台运行 |
| `recover` 失败：`Wi-Fi 未连接` | 先让手机连上可用 Wi-Fi；无线调试依赖 Wi-Fi |
| `recover` 失败：`设备处于锁屏状态` | 解锁后重试；远程无法解锁 |
| `screenshot` 超时/失败 | Android 11+ 无障碍截图在某些界面不可用，改用 `screencap`（需要 Shizuku） |
| 指令偶尔没回包 | 中继已做旧连接替换与 ping 保活；仍有异常时重启 `server.py`，再 `ping` 复验 |

## 9. 卸载

1. 手机上：设置 → 无障碍 → 关闭「远程控制」；APP 内点「断开」
2. 卸载 APP；如装了 Shizuku 且不再使用，可一并卸载
3. 电脑上停掉 `server.py` / `https_server.py`

## 10. 给 Agent 使用

仓库附带一份 Agent 技能说明：`skills/remote-phone-control/SKILL.md`，描述了大模型远程控制手机时的适用场景、安全检查、操作流程与红线。把该目录放到你所使用 Agent 的技能目录即可加载。

## 11. 许可

第三方组件 Shizuku Manager（Apache License 2.0）原样内置于 `app/src/main/assets/shizuku-manager.apk`，详见 [README](../README.md#第三方许可)。
