import http.server,os,socketserver,sys
sock,counter=sys.argv[1:]
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_POST(self):
  n=int(self.headers.get('Content-Length','0'));body=self.rfile.read(n)
  if self.path!='/v1/chat/completions' or self.headers.get('Authorization')!='Bearer fixture-key' or body!=b'{"prompt":"fixture"}':
   with open(counter+'.rejected','ab') as f:f.write(b'1');f.flush();os.fsync(f.fileno())
   self.send_error(400);return
  with open(counter,'ab') as f:f.write(b'1');f.flush();os.fsync(f.fileno())
  out=b'fixture response';self.send_response(200);self.send_header('Content-Length',str(len(out)));self.end_headers();self.wfile.write(out)
class Server(socketserver.UnixStreamServer):allow_reuse_address=False
with Server(sock,Handler) as s:
 os.chmod(sock,0o600);s.serve_forever()
