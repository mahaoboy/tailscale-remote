#!/usr/bin/env python3
import http.server, ssl, os, sys
os.chdir(os.path.dirname(os.path.abspath(__file__)))
port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
httpd = http.server.HTTPServer(('0.0.0.0', port), http.server.SimpleHTTPRequestHandler)
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain('certs/cert.pem', 'certs/key.pem')
httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
print(f"[*] HTTPS server on 0.0.0.0:{port}")
httpd.serve_forever()
