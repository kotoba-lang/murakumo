"""One-shot SSH job supervisor/cancellation; launches only the explicit command."""
import argparse, hashlib, json, os, pathlib, re, signal, subprocess, sys, time

def send(pgid, sig):
    try: os.killpg(pgid, sig)
    except ProcessLookupError: pass

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix('.tmp')
    with open(tmp, 'w') as f:
        json.dump(value, f, indent=2); f.flush(); os.fsync(f.fileno())
    tmp.replace(path)

def stop(run_id):
    path = pathlib.Path('.repeat-controls-v1') / (run_id + '.json')
    r = json.loads(path.read_text())
    if not r['live']: return
    assert r['run_id'] == run_id and r['uid'] == os.getuid()
    p = subprocess.run(['ps', '-p', str(r['child_pid']), '-o', 'uid=,pgid=,args='], capture_output=True, text=True)
    if not p.stdout.strip(): return
    uid, pgid, cmd = p.stdout.strip().split(None, 2)
    assert int(uid) == os.getuid() and int(pgid) == r['child_pid']
    assert r['worker_script'] in cmd and r['output_arg'] in cmd
    send(r['child_pid'], signal.SIGTERM)
    print(json.dumps({'stop_requested': True, 'run_id': run_id, 'pid': r['child_pid']}))

def supervise(a):
    cmd = a.command[1:] if a.command and a.command[0] == '--' else a.command
    assert cmd and 0 < a.seconds <= 1800
    scripts = ['code/repeat_train.hy', 'code/verify_repeat_artifacts.hy', 'code/check_repeat_checkpoint.hy', 'code/check_kernel_limit.hy']
    script = next((s for s in scripts if s in cmd), None)
    assert script
    out = cmd[cmd.index('--output')+1]
    live = pathlib.Path('.repeat-controls-v1') / (a.run_id + '.json')
    assert not live.exists(), 'run identity already exists; never duplicate/restart silently'
    start = time.monotonic(); p = subprocess.Popen(cmd, start_new_session=True)
    state = {'run_id': a.run_id, 'uid': os.getuid(), 'child_pid': p.pid, 'worker_script': script,
             'output_arg': out, 'live': True, 'command_sha256': hashlib.sha256(json.dumps(cmd).encode()).hexdigest()}
    write(live, state)
    cancelled = False; timed_out = False
    def cancel(signum, frame):
        nonlocal cancelled
        cancelled = True
        if p.poll() is None: send(p.pid, signal.SIGTERM)
    signal.signal(signal.SIGTERM, cancel); signal.signal(signal.SIGINT, cancel)
    while p.poll() is None:
        if time.monotonic() - start > a.seconds or cancelled:
            timed_out = not cancelled
            send(p.pid, signal.SIGTERM)
            try: p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                send(p.pid, signal.SIGKILL); p.wait()
            break
        time.sleep(.1)
    result = {'child_pid': p.pid, 'exit_status': p.returncode, 'timed_out': timed_out,
              'cancelled': cancelled, 'wall_seconds': time.monotonic()-start, 'child_reaped': p.poll() is not None,
              'deadline_seconds': a.seconds, 'run_id': a.run_id}
    write(pathlib.Path(a.receipt), result); state['live'] = False; write(live, state)
    print(json.dumps(result), flush=True)
    return 124 if timed_out else p.returncode

if __name__ == '__main__':
    p=argparse.ArgumentParser(); p.add_argument('--run-id', required=True); p.add_argument('--stop', action='store_true')
    p.add_argument('--seconds', type=int); p.add_argument('--receipt'); p.add_argument('command', nargs=argparse.REMAINDER)
    a=p.parse_args(); assert re.fullmatch(r'[a-zA-Z0-9_-]{1,100}', a.run_id)
    if a.stop: stop(a.run_id)
    else: sys.exit(supervise(a))
