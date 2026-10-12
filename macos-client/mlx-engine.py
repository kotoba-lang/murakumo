#!/usr/bin/env python3
"""One native Metal/MLX model, no VM, no CPU fallback or remote code."""
import argparse,json,time,threading,uuid,select,socket
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--model',required=True);p.add_argument('--port',type=int,default=19841);p.add_argument('--token-file',required=True);a=p.parse_args()
 import mlx.core as mx
 if not mx.metal.is_available():raise RuntimeError('Metal GPU required; CPU fallback refused')
 mx.set_default_device(mx.gpu)
 from mlx_lm import load,stream_generate
 from mlx_lm.sample_utils import make_sampler
 model,tokenizer=load(a.model,tokenizer_config={'trust_remote_code':False})
 mx.eval(model.parameters());token=Path(a.token_file).read_text().strip();lock=threading.Lock()
 import re
 if not re.fullmatch(r'[A-Za-z0-9_-]{43}',token):raise RuntimeError('Invalid private token')
 class Handler(BaseHTTPRequestHandler):
  def log_message(self,*args):pass
  def send(self,status,obj):
   b=json.dumps(obj).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
  def auth(self):
   import hmac
   if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+token):self.send(401,{'error':'authentication_required'});return False
   return True
  def do_GET(self):
   # Public local capability data only; inference still needs the private token.
   if self.path=='/v1/models':self.send(200,{'object':'list','data':[{'id':Path(a.model).name,'object':'model','owned_by':'murakumo'}]})
   elif self.path=='/health':self.send(200,{'backend':'mlx-metal','metal':True,'device':mx.device_info()['device_name'],'activeMemory':mx.get_active_memory()})
   else:self.send(404,{'error':'not_found'})
  def do_POST(self):
   if not self.auth():return
   if self.path!='/v1/chat/completions':self.send(404,{'error':'not_found'});return
   if not lock.acquire(False):self.send(429,{'error':'mlx_slot_busy'});return
   headers_sent=False
   try:
    n=int(self.headers.get('Content-Length','0'))
    if n<1 or n>1024*1024:raise ValueError('size')
    v=json.loads(self.rfile.read(n));limit=v.get('max_tokens',128)
    if v.get('model')!=Path(a.model).name or not isinstance(limit,int) or limit<1 or limit>2048:raise ValueError('model or token limit')
    if not isinstance(v.get('messages'),list) or any(x.get('role') not in ['user','assistant','system'] or not isinstance(x.get('content'),str) for x in v['messages']):raise ValueError('messages')
    if v.get('stop') is not None:raise ValueError('stop sequences unsupported')
    prompt=tokenizer.apply_chat_template(v['messages'],tokenize=False,add_generation_prompt=True,enable_thinking=False)
    started=time.monotonic();text='';last=None;stream=v.get('stream',False);cid='chatcmpl-'+uuid.uuid4().hex
    if stream:self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers();headers_sent=True
    for r in stream_generate(model,tokenizer,prompt,max_tokens=limit,sampler=make_sampler(temp=float(v.get('temperature',0)))):
     if time.monotonic()-started>180:raise ConnectionResetError('deadline')
     readable,_,_=select.select([self.connection],[],[],0)
     if readable and not self.connection.recv(1,socket.MSG_PEEK):raise ConnectionResetError('disconnected')
     text+=r.text;last=r
     if stream:
      chunk={'id':cid,'object':'chat.completion.chunk','choices':[{'index':0,'delta':{'content':r.text},'finish_reason':None}]}
      self.wfile.write(('data: '+json.dumps(chunk)+'\n\n').encode());self.wfile.flush()
    if stream:
     self.wfile.write(('data: '+json.dumps({'id':cid,'object':'chat.completion.chunk','choices':[{'index':0,'delta':{},'finish_reason':getattr(last,'finish_reason',None) or 'length'}]})+'\n\ndata: [DONE]\n\n').encode());self.wfile.flush()
    else:
     self.send(200,{'id':cid,'object':'chat.completion','model':v['model'],'choices':[{'index':0,'message':{'role':'assistant','content':text},'finish_reason':getattr(last,'finish_reason',None) or 'length'}],'usage':{'completion_tokens':last.generation_tokens if last else 0,'prompt_tokens':last.prompt_tokens if last else 0},'murakumo':{'backend':'mlx-metal','metal':True,'tokens_per_second':last.generation_tps if last else 0,'seconds':time.monotonic()-started,'activeMemory':mx.get_active_memory()}})
   except (BrokenPipeError,ConnectionResetError):pass
   except ValueError:
    if headers_sent:self.close_connection=True
    else:self.send(400,{'error':'invalid_request'})
   except Exception:
    if headers_sent:self.close_connection=True
    else:self.send(503,{'error':'mlx_inference_failed'})
   finally:mx.clear_cache();lock.release()
 ThreadingHTTPServer(('127.0.0.1',a.port),Handler).serve_forever()
if __name__=='__main__':main()
