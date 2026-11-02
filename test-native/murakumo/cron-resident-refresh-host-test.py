# Host-side controller sequencing test. Run from the repository root on macOS.
# Uses fake dependencies and substitutes BSD stat syntax; it does not test
# Linux stat, fsync, real retention, GitHub, or inference.
import os,pathlib,subprocess,tempfile
src=pathlib.Path('scripts/itonami-cron-resident-once.sh').read_text().replace('/usr/bin/stat -c %a -- "$state"','/usr/bin/stat -f %Lp "$state"')
with tempfile.TemporaryDirectory(prefix='awai147-refresh-test-') as d:
 p=pathlib.Path(d);(p/'state').mkdir(mode=0o700);(p/'work').mkdir();(p/'controller').write_text(src);(p/'controller').chmod(0o700)
 def exe(name,body):
  f=p/name;f.write_text('#!/bin/sh\nset -eu\n'+body+'\n');f.chmod(0o700);return str(f)
 planner=exe('planner','case "$1" in plan) printf "cursor/plan 1 2 %064d a b c\\nrequest\\n" 0;; advance) printf "cursor/advanced\\n";; esac')
 resident=exe('resident','printf "%s\\n" "$1" >> "$EVENTS"; if [ "$1" = refresh ]; then printf "%s\\n" "$REFRESH"; else exit 23; fi')
 bridge=exe('bridge','printf "%s\\n" "$1" >> "$EVENTS"; printf "bridge/completed\\n"')
 archiver=exe('archiver','printf "archive\\n" >> "$EVENTS"; printf "resident-archive/confirmed fixture tree\\n"')
 checkpoint=exe('checkpoint','exit 0')
 for name in ('config','definition','cursor','source'):(p/name).write_text('{}')
 args=[str(p/'controller'),planner,bridge,resident,archiver,checkpoint,str(p/'work'),str(p/'state'),'example/private',str(p/'config'),str(p/'definition'),'pin',str(p/'cursor'),str(p/'source')]
 for result,success in [('resident/retention-ready',True),('resident/replicas-refreshed',True),('resident/head-refused',False)]:
  events=p/'events';events.write_text('');env=dict(os.environ,EVENTS=str(events),REFRESH=result)
  out=subprocess.run(args,env=env,text=True,capture_output=True)
  recorded=events.read_text().splitlines()
  assert (out.returncode==0)==success,(result,out.stdout,out.stderr)
  assert recorded==(['refresh','archive','complete'] if success else ['refresh']),(result,recorded)
  if not success: assert 'cron-resident/retention-pending' in out.stderr
  print(result, 'passed', 'events='+','.join(recorded))
