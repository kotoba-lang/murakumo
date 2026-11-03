from pathlib import Path
p=Path('/private/tmp/awai150-cron-current-unity');repo=Path('/Users/junkawasaki/github/workspaces/codex/awai150-inference-replay-guard')
def extract(s,name):
 start=s.index('(defn- '+name+' ');i=start;depth=0;string=False;comment=False;escape=False
 while i<len(s):
  c=s[i]
  if comment:
   if c=='\n':comment=False
  elif string:
   if escape:escape=False
   elif c=='\\':escape=True
   elif c=='"':string=False
  elif c==';':comment=True
  elif c=='"':string=True
  elif c=='(':depth+=1
  elif c==')':
   depth-=1
   if depth==0:return s[start:i+1]+'\n'
  i+=1
 raise ValueError(name)
s=(repo/'docs/evidence/awai150-cron-controller-fixture.kotoba').read_text();prefix=s[:s.index('(defn- nc-sensitive-path?')];main=s[s.index('(defn main [] :i64'):];current=(repo/'kotoba/profile_cron_controller.kotoba').read_text()
names=['nc-path-or-tmp?','nc-resident-path?','nc-sensitive-path?','nc-path-overlap?','nc-work-paths-safe?','nc-mark','nc-advance','nc-cycle']
prefix=prefix.replace('(defn- cp-exists? [p :string] :bool (= (cp-fs "D" p "") "1"))','(defn- cp-exists? [p :string] :bool (= (cp-fs "D" p "") "1"))\n(defn- cp-absent? [p :string] :bool (= (cp-fs "D" p "") "0"))')
source=(prefix+'\n'.join(extract(current,n) for n in names)+main).replace('/var/tmp/awai150-controller','/private/tmp/awai150-cron-sequence')
(p/'sequence.kotoba').write_text(source)
