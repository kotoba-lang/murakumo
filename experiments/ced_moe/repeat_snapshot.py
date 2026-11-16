"""Read-only host counters for the one-shot repeat study."""
import datetime, json, pathlib, platform, shutil, socket, subprocess

def run(a): return subprocess.check_output(a,text=True).strip()
commands=run(['ps','-axo','pid,rss,%cpu,args'])
ps=run(['ps','-axo','pid,rss,%cpu,comm'])
print(json.dumps({'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'host':socket.gethostname(),'platform':platform.platform(),'memory_pressure':run(['memory_pressure','-Q']),'swap':run(['sysctl','vm.swapusage']),'iostat':run(['iostat','-Id','disk0']),'disk_free_bytes':shutil.disk_usage('.').free,'inference_processes_retained':[x for x in ps.splitlines() if 'llama-server' in x],'owned_model_processes':[x for x in commands.splitlines() if any(n in x for n in ['repeat_train.hy','verify_repeat_artifacts.hy','check_repeat_checkpoint.hy'])],'listeners':subprocess.run(['lsof','-nP','-iTCP:25711','-sTCP:LISTEN'],capture_output=True,text=True).stdout},indent=2))
