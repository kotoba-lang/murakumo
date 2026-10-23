"""A test peer for docs/topology-replica.md: for <seconds>, it answers every
GET with the bytes of <file>, to anyone and without a token, over HTTP/1.0.
It stands in for a hostile or outdated node; it is not part of any node path.
Usage: topology-replica-peer-20261023.py <port> <file> <seconds>"""
import http.server, sys, time
port, path, seconds = int(sys.argv[1]), sys.argv[2], float(sys.argv[3])
body = open(path, "rb").read()
class Peer(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *args): pass
srv = http.server.HTTPServer(("127.0.0.1", port), Peer)
srv.timeout = 0.2
end = time.time() + seconds
while time.time() < end: srv.handle_request()
