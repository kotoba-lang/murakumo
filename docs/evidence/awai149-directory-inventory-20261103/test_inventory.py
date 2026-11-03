# Test-only orchestration. Requires independently built diagnostic tools; does not deploy.
import argparse, os, pathlib, subprocess, struct, shutil, re, tempfile
ap=argparse.ArgumentParser()
ap.add_argument('--bootstrap', required=True, help='Amu native compiler executable')
ap.add_argument('--packager', required=True, help='Darwin diagnostic FS2 CLI packager supporting fs2-cli-stdio-read')
ap.add_argument('--helper', required=True, help='Signed bounded Darwin directory snapshot helper')
args=ap.parse_args()
for k in ('bootstrap','packager','helper'):
 setattr(args,k,str(pathlib.Path(getattr(args,k)).resolve(strict=True)))
tmp=tempfile.TemporaryDirectory(prefix='awai-inventory-test-')
p=pathlib.Path(tmp.name).resolve()
repo=pathlib.Path(__file__).resolve().parents[3]
digest=(repo/'kotoba/fleet_model_digest.kotoba').read_text()
assets=(repo/'kotoba/fleet_model_assets.kotoba').read_text()
parser=(repo/'kotoba/fleet_directory_inventory.kotoba').read_text()
source='(ns inventory-fixture (:export [main]))\n'
source+=digest[digest.index('(defn- md-arg'):digest.index('(defn- md-regular?')]
source+=digest[digest.index('(defn- md-decimal'):digest.index(';; A caller gives')]
source+=assets[assets.index('(defn- ta-name ['):assets.index('(defn- ta-id [')]
source+=parser[parser.index('(defn- di-nibble'):]
source+='\n(defn main [] :i64 (model-inventory-main))\n'
(p/'fixture.kotoba').write_text(source)
fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
try:
 r=subprocess.run([args.bootstrap,'compile','fixture.kotoba','--target','aarch64-macos','--output','fixture.kseed'],cwd=p,pass_fds=(fd,),capture_output=True,timeout=120)
 print('compile',r.returncode,r.stderr.decode()[-2000:],flush=True);assert r.returncode==0
finally:os.close(fd)
fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
try:
 r=subprocess.run([args.bootstrap,'extract-native','fixture.kseed','--symbol','main','--output','fixture.raw'],cwd=p,pass_fds=(fd,),capture_output=True,timeout=30);print('extract',r.returncode,r.stdout[-500:],flush=True);assert r.returncode==0;m=re.search(rb':offset (\d+), :length (\d+), :arity 0\}',r.stdout);assert m;offset=int(m.group(1))
finally:os.close(fd)
fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
try:
 r=subprocess.run([args.packager,'fixture-unsigned.macho','fixture.raw','fs2-cli-stdio-read',str(offset)],cwd=p,pass_fds=(fd,),capture_output=True,timeout=20);print('pack',r.returncode,r.stderr.decode(),flush=True);assert r.returncode==0
finally:os.close(fd)
shutil.copyfile(p/'fixture-unsigned.macho',p/'fixture.macho');os.chmod(p/'fixture.macho',0o700);subprocess.run(['/usr/bin/codesign','--force','--sign','-',str(p/'fixture.macho')],check=True,capture_output=True)
helper=args.helper
d=p/'valid';d.mkdir(exist_ok=True)
names=['config.json','tokenizer.json','tokenizer_config.json','chat_template.jinja','hadamard.json','mtplx_runtime.json','model.safetensors.index.json','MTPLX_PACK_MANIFEST.json','model.safetensors','mtp.safetensors','.awai-model-receipt.json']
for n in names:(d/n).touch()
def snapshot():
 fd=os.open(d,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
 try:return subprocess.run([helper],pass_fds=(fd,),capture_output=True,timeout=5)
 finally:os.close(fd)
def check(label,data,mask='255',expected=False):
 fd=os.open(p,os.O_RDONLY|os.O_DIRECTORY);assert fd==3
 try:r=subprocess.run([str(p/'fixture.macho'),mask],input=data,pass_fds=(fd,),capture_output=True,timeout=10)
 finally:os.close(fd)
 print(label,r.returncode,repr(r.stdout),flush=True)
 assert (r.returncode,r.stdout)==((0,b'inventory/match\n') if expected else (1,b'inventory/refused\n'))
r=snapshot();assert r.returncode==0;good=r.stdout;check('valid',good,expected=True)
(d/'unlisted.txt').touch();r=snapshot();assert r.returncode==0;check('unknown',r.stdout);(d/'unlisted.txt').unlink()
(d/'config.json').unlink();(d/'config.json').symlink_to('tokenizer.json');check('symlink',snapshot().stdout);(d/'config.json').unlink();(d/'config.json').touch()
(d/'config.json').unlink();check('missing',snapshot().stdout);(d/'config.json').touch()
(d/'generation_config.json').touch();optional=snapshot().stdout;check('unselected optional',optional);check('selected optional',optional,'511',True)
check('truncated',good[:-3]);check('missing newline',good[:-1]);check('uppercase',good.upper());check('bad mask',good,'0');check('bad magic',b'00'+good[2:]);check('empty',b'')
raw=bytearray.fromhex(good[:-1].decode());raw[4]=0;check('bad length',raw.hex().encode()+b'\n')
raw=bytearray.fromhex(good[:-1].decode());raw[24:26]=b'\0\0';check('zero reclen',raw.hex().encode()+b'\n')
raw=bytearray.fromhex(good[:-1].decode());reclen=struct.unpack_from('<H',raw,24)[0];raw.extend(raw[8:8+reclen]);struct.pack_into('<I',raw,4,len(raw)-8);check('duplicate',raw.hex().encode()+b'\n')
raw=bytearray.fromhex(good[:-1].decode());raw[8:16]=b'\0'*8;check('zero inode',raw.hex().encode()+b'\n')
raw=bytearray.fromhex(good[:-1].decode());raw[26:28]=b'\xff\xff';check('oversized name',raw.hex().encode()+b'\n')
print('all tests passed')
