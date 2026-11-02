import http.server,json,os,socketserver,sys,time
sock,counter,release=sys.argv[1:]
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_POST(self):
  n=int(self.headers.get('Content-Length','0'));body=self.rfile.read(n)
  try: value=json.loads(body)
  except Exception:self.send_error(400);return
  if self.path!='/v1/chat/completions' or self.headers.get('Authorization')!='Bearer fixture-key' or value.get('model')!='fixture-model' or value.get('stream') is not False or len(value.get('messages',[]))<2:
   self.send_error(400);return
  with open(counter,'ab') as f:f.write(b'1');f.flush();os.fsync(f.fileno())
  start=time.monotonic()
  while not os.path.exists(release):
   if time.monotonic()-start>15:self.send_error(503);return
   time.sleep(.05)
  out=b'fixture response';self.send_response(200);self.send_header('Content-Length',str(len(out)));self.end_headers()
  try:self.wfile.write(out)
  except BrokenPipeError:pass
class Server(socketserver.UnixStreamServer):allow_reuse_address=False
with Server(sock,Handler) as s:
 os.chmod(sock,0o600);s.serve_forever()
