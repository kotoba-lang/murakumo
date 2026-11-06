import http.server,os,socketserver,subprocess,tempfile,threading,json,hashlib
from pathlib import Path
class Server(socketserver.TCPServer):allow_reuse_address=True
class Handler(http.server.BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def do_GET(self):
  mode,size=self.path.strip('/').split('/');size=int(size);self.send_response(200)
  if mode=='length':self.send_header('Content-Length',str(size))
  elif mode=='chunked':self.send_header('Transfer-Encoding','chunked')
  self.send_header('Connection','close');self.end_headers()
  try:
   remaining=size
   while remaining:
    b=b'x'*min(8192,remaining);self.wfile.write((('%x\r\n'%len(b)).encode()+b+b'\r\n') if mode=='chunked' else b);remaining-=len(b)
   if mode=='chunked':self.wfile.write(b'0\r\n\r\n')
  except (BrokenPipeError,ConnectionResetError):pass
 def log_message(self,*args):pass
with tempfile.TemporaryDirectory(prefix='awai-curl-limit-',dir='/private/tmp') as d:
 server=Server(('127.0.0.1',0),Handler);t=threading.Thread(target=server.serve_forever,daemon=True);t.start();results=[]
 try:
  for cap in (1024,262144):
   for mode in ('length','chunked','close'):
    for size in (cap,cap+32768):
     out=Path(d)/(str(cap)+'-'+mode+'-'+str(size))
     url='http://127.0.0.1:%d/%s/%d'%(server.server_address[1],mode,size)
     r=subprocess.run(['/usr/bin/curl','--disable','--noproxy','*','--silent','--show-error','--max-time','8','--max-filesize',str(cap),'--output',str(out),url],capture_output=True,timeout=12)
     n=out.stat().st_size if out.exists() else 0;expected=0 if size==cap else 63
     if r.returncode!=expected or n>cap or (size==cap and n!=cap):raise RuntimeError((cap,mode,size,r.returncode,n,r.stderr[-200:]))
     results.append({'limit':cap,'framing':mode,'size':size,'exit':r.returncode,'fileBytes':n})
 finally:server.shutdown();server.server_close();t.join(5)
 print(json.dumps({'cases':results,'scriptSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}))
