#!/usr/bin/env python3
"""
Tailscale Remote Control — Server
Phone connects outbound to ws://<tailscale-ip>:8765/phone
Computer connects to ws://127.0.0.1:8765/client
Messages relayed between the two connections.
"""
import asyncio, json, sys, traceback, ssl, os
import websockets

phone_ws = None
client_ws = None

async def handle(ws, path):
    global phone_ws, client_ws
    ip = ws.remote_address[0] if ws.remote_address else "?"

    if path == "/phone":
        phone_ws = ws
        print(f"[+] Phone connected from {ip}")
        if client_ws is not None:
            print("[+] Client already connected — ready")
    elif path == "/client":
        client_ws = ws
        print(f"[+] Client connected from {ip}")
        if phone_ws is not None:
            print("[+] Phone already connected — ready")
        else:
            print("[!] Waiting for phone...")
    else:
        client_ws = ws
        print(f"[+] Client connected from {ip}")

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

    except websockets.exceptions.ConnectionClosed:
        pass
    except Exception as e:
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
    async with websockets.serve(handle, "0.0.0.0", port, ssl=ssl_context):
        await asyncio.Future()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[*] Bye")
