import importlib.util,pathlib,sys
sys.dont_write_bytecode=True
path='/Users/junkawasaki/github/workspaces/codex/awai147-job-archive-cursor/scripts/native/check-cron-decisions.py'
spec=importlib.util.spec_from_file_location('f',path);h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
s=pathlib.Path('/private/tmp/awai-flint-unknown-occupied.kotoba').read_text()
def put(name,code):
 global s
 a,b=h.form(s,name);s=s[:a]+code+s[b:]
cur=pathlib.Path('/Users/junkawasaki/github/workspaces/codex/awai-flint-response-contract/kotoba/profile_recovery.kotoba').read_text()
for name in ('pr-flint-busy-text','pr-flint-busy-valid?','pr-flint-busy-reserve','pr-flint-busy-release'):
 a,b=h.form(cur,name);put(name,cur[a:b])
put('main',r'''(defn main [] :i64
 (let [i "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
       t "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
       c "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
       d "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
       made (cp-mkdir "/cache")
       first (and made (pr-flint-busy-reserve "/cache" i t c))
       released (and first (pr-flint-busy-release "/cache" i t c))
       again (and released (pr-flint-busy-reserve "/cache" i t c))
       wrong (if again (pr-flint-busy-release "/cache" i t d) true)
       stale (if (and again (not wrong)) (pr-flint-busy-release "/cache" i t c) false)
       present (cp-fs "D" (str "/cache/server-" i ".busy") "")]
   (if (and first released again (not wrong) stale (= present "0")) 0 1)))''')
p=pathlib.Path('/private/tmp/awai150-busy-aba.kotoba');p.write_text(s);print('prepared',len(s))
