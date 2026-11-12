#!/usr/bin/env python3
"""
Tailscale Remote Control — Server
Phone connects outbound to ws://<tailscale-ip>:8765/phone
Computer connects to ws://127.0.0.1:8765/client
Messages relayed between the two connections.

兼容 websockets 新旧两套实现：
  - < 14: handler(ws, path)
  - >= 14: handler(ws)，path 在 ws.request.path
"""
import asyncio, json, sys, traceback, ssl, os
import websockets

phone_ws = None
client_ws = None


def request_path(ws, fallback="/client"):
    req = getattr(ws, "request", None)
    path = getattr(req, "path", None)
    if path is None:
        path = getattr(ws, "path", None)
    return path or fallback


async def handle(ws, path=None):
    global phone_ws, client_ws
    ip = ws.remote_address[0] if ws.remote_address else "?"
    if path is None:
        path = request_path(ws)

    if path == "/phone":
        old = phone_ws
        phone_ws = ws
        if old is not None and old is not ws:
            try:
                await old.close(1001, "replaced by new phone connection")
            except Exception:
                pass
        print(f"[+] Phone connected from {ip}")
        if client_ws is not None:
            print("[+] Client already connected — ready")
    elif path == "/client":
        old = client_ws
        client_ws = ws
        if old is not None and old is not ws:
            try:
                await old.close(1001, "replaced by new client connection")
            except Exception:
                pass
        print(f"[+] Client connected from {ip}")
        if phone_ws is not None:
            print("[+] Phone already connected — ready")
        else:
            print("[!] Waiting for phone...")
    else:
        client_ws = ws
        print(f"[+] Client connected from {ip} (unknown path {path})")

    try:
        while True:
            msg = await ws.recv()
            try:
                data = json.loads(msg)
            except json.JSONDecodeError:
                continue

            # Phone sent a result → forward to client
            if phone_ws is ws and client_ws is not None:
                try:
                    await client_ws.send(json.dumps(data))
                except Exception as e:
                    print(f"[-] Forward to client failed: {e}")

            # Client sent a command → forward to phone
            if client_ws is ws and phone_ws is not None:
                try:
                    await phone_ws.send(json.dumps(data))
                    print(f"[>>] {data.get('cmd', '?')}")
                except Exception as e:
                    print(f"[-] Forward to phone failed: {e}")

    except Exception as e:
        if not isinstance(e, websockets.exceptions.ConnectionClosed):
            print(f"[-] Error: {e}")
            traceback.print_exc()
    finally:
        if ws is phone_ws:
            phone_ws = None
            print("[-] Phone disconnected")
        if ws is client_ws:
            client_ws = None
            print("[-] Client disconnected")


async def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    cert_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "certs")
    ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ssl_context.load_cert_chain(
        certfile=os.path.join(cert_dir, "cert.pem"),
        keyfile=os.path.join(cert_dir, "key.pem")
    )
    print(f"[*] WebSocket server on 0.0.0.0:{port} (SSL/WSS)")
    print(f"[*] Phone:   wss://<tailscale-ip>:{port}/phone")
    print(f"[*] Computer: wss://127.0.0.1:{port}/client")
    # max_size=None: 截图 base64 会超过默认 1MiB，否则连接直接被 1009 断开
    async with websockets.serve(handle, "0.0.0.0", port, ssl=ssl_context,
                                max_size=None, ping_interval=20, ping_timeout=30):
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[*] Bye")
