# Test-only dependency slicer from a previously assembled native unity.
from pathlib import Path
import re
p=Path('/private/tmp/awai150-lease-time-guard')
def forms(s):
 out=[];i=0;n=len(s)
 while i<n:
  if s[i]==';':
   j=s.find('\n',i);i=n if j<0 else j+1;continue
  if s[i]!='(':i+=1;continue
  start=i;depth=0;string=False;esc=False;comment=False
  while i<n:
   c=s[i]
   if comment:
    if c=='\n':comment=False
   elif string:
    if esc:esc=False
    elif c=='\\':esc=True
    elif c=='"':string=False
   elif c==';':comment=True
   elif c=='"':string=True
   elif c=='(':depth+=1
   elif c==')':
    depth-=1
    if depth==0:i+=1;break
   i+=1
  f=s[start:i];m=re.match(r'\(def(?:n-?|)\s+([^\s\[()]+)',f)
  if m:out.append((m.group(1),f))
 return out
def tokens(s):
 s=re.sub(r'"(?:\\.|[^"\\])*"',' ',s)
 return set(re.findall(r'[^\s()\[\]{}";]+',s))
base=Path('docs/evidence/awai150-current-cron-unity-20261103/unity.kotoba').read_text(); fs=forms(base); pairs=dict(fs)
new=dict(forms(Path('kotoba/fleet_lease_core.kotoba').read_text()))
for k in ('valid-value?','held-now?','decide','decide-epoch','test-lease-decide'):pairs[k]=new[k]
test='''(defn main [] :i64
 (let [good (cp-bytes "{\\"class\\":\\"task\\",\\"epoch\\":1,\\"node\\":\\"bot\\",\\"revision\\":1,\\"slot\\":0,\\"until\\":200000}")
       old (cp-bytes "{\\"class\\":\\"task\\",\\"epoch\\":1,\\"node\\":\\"bot\\",\\"revision\\":1,\\"slot\\":0,\\"until\\":-1}")]
  (if (and (= (recovery-lease-problem good "task" "bot" 1 100000) "recovery/lease-admitted")
           (= (recovery-lease-problem old "task" "bot" 1 100000) "recovery/lease-value")
           (test-lease-decide)) 0 1)))'''
pairs.update({k:v for k,v in forms(Path('kotoba/profile_resident.kotoba').read_text()) if k == 'rt-lease-bound?'})
test=test.replace('(test-lease-decide)', '(test-lease-decide) (rt-lease-bound? (cp-bytes "{\\\"task\\\":\\\"task\\\"}") good 1 100000) (not (rt-lease-bound? (cp-bytes "{\\\"task\\\":\\\"task\\\"}") old 1 100000)) (not (rt-lease-bound? (cp-bytes "{\\\"task\\\":\\\"task\\\"}") good 2 100000))')
test=test.replace('(test-lease-decide)', '(test-lease-decide) (not (rt-lease-bound? (vector-alloc 0) (cp-bytes "{") 1 100000)) (not (rt-lease-bound? (vector-alloc 0) (vector-alloc 0) 1 100000))')
pairs['main']=test
need={'main'};stack=['main']
while stack:
 for k in tokens(pairs[stack.pop()]):
  if k in pairs and k not in need:need.add(k);stack.append(k)
seen=set(); selected=[]
for k,f in fs:
 if k in need and k != "main" and k not in seen:selected.append(pairs[k]);seen.add(k)
for k in need-seen-{'main'}: selected.append(pairs[k])
(p/'recovery.kotoba').write_text('(ns recovery-test (:export [main]))\n'+'\n'.join(selected)+'\n'+test+'\n')
print('selected',len(need),'functions')
