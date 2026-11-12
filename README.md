# Tailscale Remote Control

通过 WebSocket + Tailscale 实现远程控制 Android 手机的系统。手机主动出站连接电脑的 WebSocket 服务端（WSS），电脑通过这条连接推送 UI 控制指令，手机执行后回传结果。

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
  └── MainActivity ── 配置界面，显示连接状态和日志
```

## 功能

| 命令 | 功能 |
|------|------|
| `ping` | 检查连接 + 无障碍状态 |
| `check` | 完整状态检查 |
| `screensize` | 获取屏幕分辨率 |
| `tap x y` | 点击坐标 |
| `longtap x y` | 长按 |
| `swipe x1 y1 x2 y2 [dur]` | 滑动手势 |
| `type text` | 输入文字 |
| `back` / `home` | 返回 / 主页 |
| `findtext "text"` | 查找屏幕文字，返回坐标 |
| `taptext "text"` | 点击包含文字的元素 |
| `gettext` | 读取屏幕所有文字 |
| `dump` | 获取完整 UI 节点树 |
| `screenshot` | 截屏（保存到 /tmp/screenshot.png） |
| `open <package>` | 启动指定 APP |
| `shell 'cmd'` | 执行 shell 命令 |
| `update [url]` | 下载并安装 APK 更新 |

## 使用

### 1. 启动服务端

```bash
# WebSocket 中继服务器（WSS，端口 8765）
python3 server.py

# HTTPS 文件服务器（端口 8080，提供 APK 下载）
python3 https_server.py 8080
```

### 2. 构建 APP

```bash
./build.sh
```

需要 JDK 21+ 和 Android SDK（build-tools 35.0.1, platforms android-35）。

### 3. 安装 APP 到手机

1. 浏览器打开 `https://<tailscale-ip>:8080/build/app-debug.apk` 下载安装
2. 设置 → 无障碍 → 启用"远程控制"
3. 打开 APP，输入 `wss://<tailscale-ip>:8765/phone`，点连接

### 4. 远程操作

```bash
# 交互式
python3 remotectl.py

# 单条命令
python3 remotectl.py ping
python3 remotectl.py tap 540 1188
python3 remotectl.py open settings
python3 remotectl.py screenshot
```

## 安全

- WebSocket 使用 WSS（TLS 加密）
- 手机端信任所有证书（debug build，生产环境需替换）
- 指令通过 Tailscale 网络传输，不暴露公网端口

## 文件结构

```
├── app/                        # Android APP 源码
│   └── src/main/
│       ├── java/com/mahao/remotecontrol/
│       │   ├── MainActivity.java
│       │   ├── WsRemoteService.java
│       │   ├── RemoteAccessibilityService.java
│       │   ├── AppContext.java
│       │   └── CrashHandler.java
│       ├── res/
│       └── AndroidManifest.xml
├── server.py                   # WebSocket 中继服务器
├── remotectl.py                # CLI 客户端
├── https_server.py             # HTTPS 文件服务器
├── build.sh                    # 构建脚本
├── libs/                       # 依赖 jar
└── certs/                      # TLS 证书（不上传）
```
