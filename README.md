# Tailscale Remote Control

通过 WebSocket + Tailscale 实现远程控制 Android 手机的系统。手机主动出站连接电脑的 WebSocket 服务端（WSS），电脑通过这条连接推送指令，手机执行后回传结果。适合让大模型 / Agent 在安全边界内远程操作手机。

## 下载与文档

- **APK 下载**：[最新 Release](https://github.com/mahaoboy/tailscale-remote/releases/latest)（`app-debug.apk`）
- **使用说明书**：[docs/USAGE.md](docs/USAGE.md) —— 从零部署、安装、命令、自愈、排障、安全
- **Agent 技能**：[skills/remote-phone-control/SKILL.md](skills/remote-phone-control/SKILL.md) —— 大模型远程控制手机的适用场景、操作流程与安全红线

执行层有两套：

- **无障碍服务**（只需在手机上手动开启，无需 root / 电脑）：点击、滑动、输入、读屏、截图（Android 11+）。
- **Shizuku shell**（可选，安装包已内置在 APP 里，装好并授权即可，无需 root / 电脑）：以 `shell`(uid 2000) 身份执行 `pm`、`settings`、`appops`、`am`、`dumpsys`、`input`、`screencap` 等命令，即"adb 能力"。Shizuku 不可用时自动退回应用自身 uid，返回结果里会带 `backend=app`。

## 架构

```
电脑 (server.py + remotectl.py)
  │
  ├── WSS 服务器 (端口 8765) ── 中继指令
  ├── HTTPS 服务器 (端口 8080) ── 提供 APK 下载
  │
  └── Tailscale 网络 (100.68.64.94)
        │
        ▼
手机 (com.mahao.remotecontrol)
  ├── WsRemoteService ── WebSocket 客户端，接收指令
  ├── RemoteAccessibilityService ── 无障碍服务，执行 UI 操作
  ├── Shell ── 统一执行层：Shizuku(shell uid) 优先，缺失时退回应用 uid
  ├── ShizukuHelper ── Shizuku 绑定/授权状态
  └── MainActivity ── 配置界面，显示连接状态、Shizuku 状态和日志
```

## 功能

无障碍命令（仅需无障碍权限）：

| 命令 | 功能 |
|------|------|
| `ping` | 检查连接 + 无障碍状态 + shell 后端 |
| `check` | 完整状态检查（ws / a11y / shizuku / backend / 开机计数 / 自愈状态 / 前台应用 / 构建时间） |
| `recover` | Shizuku 自愈：开 Wi-Fi → 开无线调试 → 打开 Shizuku 点「启动」→ 等就绪 |
| `screensize` | 获取屏幕分辨率 |
| `uis [filter]` | 紧凑列出当前界面的可操作节点（序号 / 类名 / 文字 / 坐标 / 状态） |
| `uitap <i> [filter]` | 按 `uis` 的序号点击，不用手算坐标 |
| `tap x y` | 点击坐标 |
| `longtap x y` | 长按 |
| `swipe x1 y1 x2 y2 [dur]` | 滑动手势（dur 50–3000ms，短于 50ms 会退化成 fling） |
| `type text` | 输入文字（支持中文，走 `ACTION_SET_TEXT`） |
| `back` / `home` | 返回 / 主页 |
| `findtext "text" [i]` | 查找屏幕文字，返回坐标；`i` 指定第几个匹配 |
| `taptext "text" [i]` | 点击包含文字的元素（自动上溯可点击父节点） |
| `waittext "text" [sec]` | 轮询等待文字出现 |
| `gettext` | 读取屏幕所有文字 |
| `dump` | 获取完整 UI 节点树 |
| `screenshot` | 截屏（有 Shizuku 时走 screencap，否则走无障碍截图） |
| `open <package>` | 启动指定 APP（有 Shizuku 时可用 monkey 兜底） |

Shizuku 命令（需要 Shizuku 已启动并授权）：

| 命令 | 功能 |
|------|------|
| `shell 'cmd'` | 任意 shell 命令（PowerShell 风格参数需自行加引号） |
| `screencap` | `screencap -p`，保存到 /tmp/screenshot.png |
| `input <args...>` | `input tap 500 800` / `input swipe 1 2 3 4 300` / `input text hello` |
| `keyevent <code>` | `input keyevent 3`（HOME）、`26`（POWER）等 |
| `grant <pkg> <perm>` | `pm grant`，静默授予运行时权限 |
| `revoke <pkg> <perm>` | `pm revoke` |
| `settings <args...>` | `settings put global airplane_mode_on 0` 等 |
| `appops <args...>` | `appops set <pkg> MANAGE_EXTERNAL_STORAGE allow` 等 |
| `am <args...>` | `am start -n com.example/.MainActivity` 等 |
| `dumpsys [args...]` | `dumpsys battery` / `dumpsys window` 等 |
| `props [key]` | `getprop`，如 `props ro.product.model` |
| `put <local> [dest]` | 把电脑上的文件推到手机（默认 `/data/local/tmp/<name>`，仅允许 tmp/sdcard） |
| `install <path>` | `pm install -r`；`/sdcard` 路径会自动复制到 `/data/local/tmp` 再装 |
| `uninstall <pkg> [--keep]` | `pm uninstall [-k]` |
| `shizuku [request]` | 查看 Shizuku 状态 / 弹出授权框 |
| `update [url]` | 下载并安装 APK 更新（下载走长超时客户端，安装前自动落到 `/data/local/tmp`） |

除 `shell` 外，所有参数都会被 shell 转义后再拼接，避免命令注入。`am/settings/appops/dumpsys/input` 也支持把整条命令加引号传入（CLI 会自动拆分 token）。

## 使用

### 1. 启动服务端

```bash
# WebSocket 中继服务器（WSS，端口 8765）
python3 server.py

# HTTPS 文件服务器（端口 8080，提供 APK 下载）
python3 https_server.py 8080
```

服务端使用 `websockets` 库，新旧 API 都兼容（`< 14` 与 `>= 14`），并把 `max_size` 设为 `None`，否则截图 base64 超过默认 1MiB 会被 1009 断开。

### 2. 构建 APP

```bash
./build.sh
```

本工程使用 Gradle（AGP 8.7.3 / Gradle 8.9 / JDK 17 / compileSdk 35 / minSdk 26），wrapper 已随仓库提供，`./build.sh` 会执行 `./gradlew assembleDebug` 并把产物复制到 `build/app-debug.apk`（保持 HTTPS 下载路径不变）。

构建机需要：JDK 17、Android SDK（platform 35 + build-tools 35）。用 Android Studio 打开本目录直接 Build 也可以。

依赖：`dev.rikka.shizuku:api` + `dev.rikka.shizuku:provider`（**13.0.0**）、OkHttp 3.14.9、Gson 2.11.0，均由 Gradle 自动下载。Shizuku 库固定在 13.0.0 是因为 13.1.x 把 `Shizuku.newProcess` 改成了 private，而本工程用它获取 shell uid 的 `Process`；若以后要升级到 13.1+，需要改成 `bindUserService` + AIDL 的官方用法。

### 3. （可选）启用 Shizuku

APP 内置了 Shizuku Manager 安装包（`assets/shizuku-manager.apk`，13.6.0），首页的 Shizuku 提示行或按钮点进去就是三步引导页：

1. **安装 Shizuku** —— 点「安装内置 Shizuku」直接调起系统安装器，不用去应用商店下载；首次会要求允许「安装未知来源应用」。
2. **启动服务** —— 点「打开 Shizuku」，按它首页的说明启动服务（Android 11+ 选「通过无线调试启动」，配对一次即可，不需要电脑）。引导页上的「无线调试设置」可以直接跳到开发者选项。
3. **授权本应用** —— 服务起来后引导页会自动弹出 Shizuku 授权框，选「允许」。

状态行变成「Shizuku 已授权：shell 能力可用」即生效，`ping` 会返回 `shell:shizuku`。三步状态每秒自动刷新，装完/启动完不用手动重进。

内置安装只能省掉「下载安装包」这一步，**启动服务仍然必须由用户手动确认一次**：普通应用在自己的 uid 下无法提权到 shell(2000)，只能由用户授权过的 Shizuku 服务代为执行。

没有 Shizuku 时一切照旧，只是 `pm/settings/appops/am/dumpsys/input/screencap` 会以应用 uid 执行并返回失败，`ping` 返回 `shell:app`。

### 4. 安装 APP 到手机

1. 浏览器打开 `https://<tailscale-ip>:8080/build/app-debug.apk` 下载安装
2. 设置 → 无障碍 → 启用"远程控制"
3. 打开 APP，输入 `wss://<tailscale-ip>:8765/phone`，点连接

> 构建方式从手工 `javac/d8` 换成 Gradle 后，签名用的是 Gradle 的 debug keystore。**第一次装新包前需要先卸载旧版本**（签名不同，`pm install -r` 会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。卸载后 Shizuku 授权需要重新确认一次。

### 4.1 重启 / 掉线自动恢复

重启手机后 Shizuku（非 root）不会自己起来，本应用做了一套有限的自愈：

- **开机自启**：`BootReceiver` 收到 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 后用已保存的地址拉起前台服务并重连，前台服务类型是 `connectedDevice`（Android 15/16 禁止开机启动 `dataSync` 类型）。
- **连上后自愈**：开机 15 分钟内首次连上服务端且 Shizuku 未运行，自动执行一次 `recover`（最多 5 次，首轮 8s 后，后续间隔 60s）。每次开机只跑一轮，失败不会反复弹界面。
- **手动自愈**：`python3 remotectl.py recover`，最多等 150s，返回 `shizuku ready` 或具体失败原因。
- `check` 会带上 `boot:<开机次数> up:<开机分钟数> autorecover:done|pending|skip recstep:<当前步骤>`，用来判断自愈是否已执行。

自愈只做「打开开关 / 点按钮」，不写任何系统设置值。已知限制：

- 需要**设备已解锁**（锁屏时直接返回 `fail: 设备处于锁屏状态`）。
- 需要**能连上可用 Wi-Fi**（无线调试依赖 Wi-Fi；手机没连 Wi-Fi 时返回明确失败原因，不会去猜密码）。
- **首次必须在 Shizuku 里手动配对一次**（配对依赖 Shizuku 的通知交互，普通应用无法代填）。配对过一次后，之后重启都可以自动启动。
- 没有无障碍服务时无法自愈（`fail: 无障碍服务未连接`）。


### 5. 远程操作

```bash
# 交互式
python3 remotectl.py

# 单条命令
python3 remotectl.py ping
python3 remotectl.py tap 540 1188
python3 remotectl.py open settings
python3 remotectl.py screenshot
python3 remotectl.py screencap
python3 remotectl.py input tap 540 1188
python3 remotectl.py keyevent 3
python3 remotectl.py grant com.example.app android.permission.CAMERA
python3 remotectl.py settings put global airplane_mode_on 0
python3 remotectl.py dumpsys battery
python3 remotectl.py props ro.product.model
```

单个命令的超时时间按命令类型设置（`install`/`update` 200s，`dumpsys`/`am`/`shell` 40s，其余 20s），并且会按 `id` 匹配返回，不会把上一条超时后迟到的结果当成本次结果。

## 安全

- WebSocket 使用 WSS（TLS 加密），手机端信任所有证书（debug build，生产环境需替换）
- 指令通过 Tailscale 网络传输，不暴露公网端口
- **没有任何客户端认证**：能连到 `8765` 的人就能控制手机。请用 Tailscale ACL 把 `/phone` 和 `/client` 限定在可信设备之间
- Shizuku 授权等同于把 shell 能力交给本应用，`shell` 命令是有意保留的任意命令执行入口，不要在没有 ACL 的环境下开启

## 第三方许可

- `app/src/main/assets/shizuku-manager.apk` 是 [Shizuku](https://github.com/RikkaApps/Shizuku) Manager 13.6.0（`moe.shizuku.privileged.api`，作者 RikkaW），按 Apache License 2.0 原样分发，未做任何修改；升级时替换该文件并同步 `ShizukuInstaller.BUNDLED_VERSION` 与引导页文案即可。

## 文件结构

```
├── app/                        # Android APP 源码
│   └── src/main/
│       ├── java/com/mahao/remotecontrol/
│       │   ├── MainActivity.java
│       │   ├── ShizukuOnboardActivity.java # 安装/启动/授权三步引导
│       │   ├── ShizukuInstaller.java       # 内置 APK：拷到缓存并拉起系统安装器
│       │   ├── ShizukuApkProvider.java     # 只读暴露缓存里的内置 APK
│       │   ├── WsRemoteService.java
│       │   ├── RemoteAccessibilityService.java
│       │   ├── ShizukuRecovery.java        # 重启后 Shizuku 自愈序列
│       │   ├── BootReceiver.java           # 开机 / 覆盖安装后自动拉起服务
│       │   ├── Shell.java                  # 统一执行层
│       │   ├── ShizukuHelper.java          # Shizuku 绑定/授权
│       │   ├── AppContext.java
│       │   └── CrashHandler.java
│       ├── assets/shizuku-manager.apk      # 内置 Shizuku 13.6.0（原样分发）
│       ├── res/
│       └── AndroidManifest.xml
├── settings.gradle             # Gradle 配置
├── build.gradle
├── app/build.gradle
├── gradle/wrapper/
├── server.py                   # WebSocket 中继服务器
├── remotectl.py                # CLI 客户端
├── https_server.py             # HTTPS 文件服务器
├── build.sh                    # 构建脚本
├── docs/USAGE.md               # 使用说明书
├── skills/remote-phone-control/SKILL.md  # Agent 技能说明
└── certs/                      # TLS 证书（不上传）
```
