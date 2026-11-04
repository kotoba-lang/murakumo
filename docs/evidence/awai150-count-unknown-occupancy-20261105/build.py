import importlib.util, pathlib, sys
sys.dont_write_bytecode=True
path='/Users/junkawasaki/github/workspaces/codex/awai147-job-archive-cursor/scripts/native/check-cron-decisions.py'
spec=importlib.util.spec_from_file_location('f',path);h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
s=pathlib.Path('/private/tmp/awai-flint-integrated-check.kotoba').read_text()
def put(name, code):
 global s
 a,b=h.form(s,name);s=s[:a]+code+s[b:]
stubs={
'pr-runtime?':'(defn- pr-runtime? [a :vector-i64 b :vector-i64 c :vector-i64] :bool true)',
'pr-growth-runtime?':'(defn- pr-growth-runtime? [a :vector-i64 b :vector-i64 c :vector-i64 d :vector-i64 e :vector-i64] :bool false)',
'pr-wall-ms':'(defn- pr-wall-ms [] :i64 106500)',
'pr-unit-state':'(defn- pr-unit-state [a :string] :string "exited")',
'pr-submit':'(defn- pr-submit [a :string b :string c :string d :i64 e :vector-i64] :bool (cp-write "/unexpected-submit" [1]))',
'pr-count':'''(defn- pr-count [runtime :vector-i64 cache :string seconds :i64 body :vector-i64 started :i64 deadline :i64 reuse :bool] :i64
 (let [id (pr-count-id runtime body)
       ok (and (not reuse)
               (cp-write (str cache "/" id ".count-request.json") body)
               (cp-write (str cache "/" id ".count-dispatch") (cp-bytes "itonami.inference-dispatch/1\\n"))
               (cp-write (str cache "/" id ".count-response.json") (cp-bytes "{\\"input_tokens\\":20}")))]
   (if ok 20 -1)))''',
}
for name,code in stubs.items():put(name,code)

current=pathlib.Path('/Users/junkawasaki/github/workspaces/codex/awai-flint-response-contract/kotoba/profile_recovery.kotoba').read_text()
for name in ('pr-step-range','pr-flint-count-only-release','pr-flint-busy-reserve','pr-flint-busy-release','pr-flint-busy-reconcile-release','pr-flint-finish','pr-window-seconds','pr-wait'):
 a,b=h.form(current,name);put(name,current[a:b])
put('pr-count','(defn- pr-count [runtime :vector-i64 cache :string seconds :i64 body :vector-i64 started :i64 deadline :i64 reuse :bool] :i64 -1)')
main=r'''(defn main [] :i64
 (let [instance "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
       old (cp-bytes "{\"next\":1,\"previous\":\"none\",\"task\":\"task\"}")
       runtime (cp-bytes (str "{\"authorizationFile\":\"/auth\",\"contextTokens\":8192,\"endpoint\":\"http://localhost/v1/chat/completions\",\"kind\":\"itonami.readonly-profile-runtime-local-flint/1\",\"maxTokens\":6144,\"model\":\"flint\",\"reasoningMemory\":\"disabled\",\"serverInstance\":\"" instance "\",\"socket\":\"/sock\",\"task\":\"task\"}"))
       setup (and (cp-mkdir "/repo") (cp-mkdir "/repo/profile") (cp-mkdir "/cache")
                  (cp-write "/repo/profile/itonami.edn" (cp-bytes "profile"))
                  (cp-write "/repo/profile/SOUL.md" (cp-bytes "role"))
                  (cp-write "/repo/state.json" old))
       result (if setup (pr-step-range "/repo" runtime "/cache" 1 10 2 64 100000 110000 1) "setup-failed")
       busy (cp-fs "D" (str "/cache/server-" instance ".busy") "")]
  (if (and (= result "profile/context-budget-unknown-or-insufficient") (= busy "1")
           (= (cp-fs "D" "/unexpected-submit" "") "0")
           (= (cp-cid (pr-read-bounded "/repo/state.json" 16384)) (cp-cid old))) 0 1)))'''
put('main',main)
pathlib.Path('/private/tmp/awai-flint-unknown-occupied.kotoba').write_text(s)
print('prepared',len(s))
