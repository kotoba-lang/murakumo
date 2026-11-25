"""Stdlib checks and descriptive arithmetic on dated receipts; no tensor execution."""
from paired_core import descriptive, gradient_changes, summarize_pairs
from study_control import HERE, write
import hashlib, json, math, pathlib, re
GIB=1024**3
SOURCE=HERE.parents[2]/'experiments/ced_moe'
KINDS=['weights','master','m','v']

def read(path):return json.loads((HERE/path).read_text())
def changed(a,b):return {k:sum(a[k][n]!=b[k][n] for n in a[k]) for k in KINDS}
def swap(row):return float(re.search(r'used = ([0-9.]+)M',row['swap'])[1])
def group(seed,mode,phase):
    base=pathlib.Path(f'phases/actual-s{seed}-{mode}-{phase}')
    rows=[read(base/f'rank{r}.json') for r in range(10)]
    supervisors=[read(base/f'rank{r}-supervisor.json') for r in range(10)]
    first=rows[0]
    o=first['options'];assert o['seed']==seed and o['state_placement']==mode and o['sequence']==64 and o['batch']==1 and o['eval_batches']==8 and o['eval_every']==16 and o['eval_seed']==936 and o['diagnostic_eval'] and o['diagnostic_seed']==1936 and o['diagnostic_batches']==32 and not o['confirmation']
    assert len({r['host'] for r in rows})==10
    for rank,r in enumerate(rows):
        assert r['rank']==rank and r['parameters']==3003341824 and r['world_size']==10
        for k in ['options','config','partition','source_sha256','data_manifest_sha256','input_sha256','initial_validation_ce','final_validation_ce','evaluations','diagnostic_validation']:
            assert r[k]==first[k], k
        for n,h in r['source_sha256'].items():assert hashlib.sha256((SOURCE/n).read_bytes()).hexdigest()==h
        g=r['memory_enforcer']; assert g['requested_bytes']==10*GIB and g['kernel_limit_bytes']==9*GIB and g['active_fatal'] and g['inactive_fatal'] and g['readback_verified']
        assert r['process_memory']['lifetime_peak_phys_footprint_bytes']<9*GIB
        p=r['partition'][rank]; assert p['parameters']>0
        sb=4*p['parameters'];io=r['logical_optimizer_io']
        for k in ['master','m','v']:
            assert io['read_bytes'][k]==34*sb #32 updates + initial/final fingerprints
            assert r['fingerprint_state_reads']['initial'][k]==r['fingerprint_state_reads']['final'][k]==sb
            assert io['write_bytes'][k]==(33 if phase=='prefix' else 32)*sb
            if phase=='prefix':assert io['restore_file_bytes'][k]==0
            else:assert io['restore_file_bytes'][k]>sb
        assert len(r['trace'])==32 and r['global_training_tokens_counted_once']==2048
        assert all(math.isfinite(x['objective']) and math.isfinite(x['global_gradient_norm']) and x['learning_rate']==.0003*min(1,x['step']/4) for x in r['trace'])
        assert r['trace'][0]['first_update_gradient_sha256'] and all(x['first_update_gradient_sha256'] is None for x in r['trace'][1:])
    assert all(x['exit_status']==0 and x['child_reaped'] and not x['timed_out'] for x in supervisors)
    return rows,supervisors

def measure(groups):
    n=2048*len(groups)
    seconds=sum(max(s['wall_seconds'] for s in sups) for rows,sups in groups)
    phase_seconds=sum(max(r['training_phase_seconds'] for r in rows) for rows,sups in groups)
    update_seconds=sum(max(sum(x['step_seconds'] for x in r['trace']) for r in rows) for rows,sups in groups)
    flat=[r for rows,sups in groups for r in rows]
    allocated_training=0
    for rows,sups in groups:
        r=rows[0]
        nf=32+len(r['evaluations'])*8+(32 if r['diagnostic_validation'] else 0)
        forward=sum(r['mesh'][k]['useful_owner_payload_bytes'] for k in ['embedding_forward','stage_forward','loss'])
        backward=sum(r['mesh'][k]['useful_owner_payload_bytes'] for k in ['head_backward','stage_backward'])
        assert forward % nf==0 and forward//nf==2279272 and backward==71699200
        allocated_training+=forward//nf*32+backward
    return {'global_tokens_counted_once':n,'all_in_supervisor_seconds':seconds,'all_in_tok_s':n/seconds,'phase_dev_inclusive_seconds':phase_seconds,'phase_dev_inclusive_tok_s':n/phase_seconds,'update_seconds':update_seconds,'update_only_tok_s':n/update_seconds,'max_learner_footprint_gib':max(r['process_memory']['lifetime_peak_phys_footprint_bytes'] for r in flat)/GIB,'fixed_shape_allocated_training_boundary_bytes_global_once':allocated_training,'communication_allocation_scope':'Measured per-channel logical payload split by equal-shape fullforward counts; excludes scalar globalnorm/barriers/metadata and allreduce duplication, not direct train-only wire','logical_boundary_bytes_global_once':sum(sum(c['useful_owner_payload_bytes'] for c in rows[0]['mesh'].values()) for rows,sups in groups),'whole_nic_tx_bytes_per_rank':[sum(rows[i]['interface_delta']['tx'] for rows,sups in groups) for i in range(10)],'whole_nic_rx_bytes_per_rank':[sum(rows[i]['interface_delta']['rx'] for rows,sups in groups) for i in range(10)],'process_disk_read_bytes_all_ranks':sum(r['process_io']['read_bytes'] for r in flat),'process_disk_write_bytes_all_ranks':sum(r['process_io']['write_bytes'] for r in flat),'logical_state_read_bytes_all_ranks':{k:sum(r['logical_optimizer_io']['read_bytes'][k] for r in flat) for k in ['master','m','v']},'logical_state_write_bytes_all_ranks':{k:sum(r['logical_optimizer_io']['write_bytes'][k] for r in flat) for k in ['master','m','v']},'fingerprint_read_bytes_all_ranks':sum(sum(sum(v.values()) for v in r['fingerprint_state_reads'].values()) for r in flat),'step_time_windows':[{'start_step':rows[0]['start_step'],'end_step':rows[0]['end_step'],'global_maxrank_seconds_per_update':[max(r['trace'][i]['step_seconds'] for r in rows) for i in range(32)],'first16_mean_seconds':sum(max(r['trace'][i]['step_seconds'] for r in rows) for i in range(16))/16,'last16_mean_seconds':sum(max(r['trace'][i]['step_seconds'] for r in rows) for i in range(16,32))/16} for rows,sups in groups],'exclusive_wire_bytes':None,'device_nand_bytes':None}

conditions={};cohorts={};all_streams={}
for seed in [936,937,938]:
    for mode in ['ram','nvme']:
        if not (HERE/f'seed{seed}-{mode}/COMPLETE.json').exists():continue
        groups=[group(seed,mode,p) for p in ['prefix','suffix','replay']]
        pre,suf,replay=[g[0] for g in groups]; first=pre[0];final=suf[0];again=replay[0]
        for a,b,c in zip(pre,suf,replay):
            assert a['final_state_sha256']==b['initial_state_sha256']==c['initial_state_sha256']
            assert a['final_validation_ce']==b['initial_validation_ce']==c['initial_validation_ce']
            assert b['input_sha256']==c['input_sha256'] and b['end_step']==c['end_step']==64
        o=first['options'].copy();o.pop('state_placement');o.pop('state_offload')
        cohorts[seed,mode]=(o,first['config'],first['partition'],first['source_sha256'],first['data_manifest_sha256'])
        all_streams[seed,mode]=first['input_sha256']+final['input_sha256']
        r=measure(groups[:2]);r.update({'seed':seed,'placement':mode,'initial_dev_ce':first['initial_validation_ce'],'step32_dev_ce':first['final_validation_ce'],'final_dev_ce':final['final_validation_ce'],'final_secondary_dev_ce':final['diagnostic_validation']['ce'],'dev_curve':first['evaluations']+final['evaluations'][1:],'replay':measure(groups[2:]),'replay_final_dev_ce':again['final_validation_ce'],'replay_final_secondary_dev_ce':again['diagnostic_validation']['ce'],'replay_minus_main_dev_ce':again['final_validation_ce']-final['final_validation_ce'],'replay_minus_main_secondary_dev_ce':again['diagnostic_validation']['ce']-final['diagnostic_validation']['ce'],'replay_changed_terminal_shards':{k:sum(changed(b['final_state_sha256'],c['final_state_sha256'])[k] for b,c in zip(suf,replay)) for k in KINDS},'replay_changed_first_gradient_shards':sum(gradient_changes(b['trace'][0]['first_update_gradient_sha256'],c['trace'][0]['first_update_gradient_sha256']) for b,c in zip(suf,replay)),'boundary_hashes_ce_inputs_exact':True,'save32_64_max_seconds':[max(r['checkpoint']['seconds'] for r in rows) for rows,sups in groups[:2]],'resume_main_max_seconds':max(r['restored']['seconds'] for r in suf),'resume_replay_max_seconds':max(r['restored']['seconds'] for r in replay)})
        before=read(f'snapshots/actual-s{seed}-{mode}-prefix-before.json'); after=read(f'snapshots/actual-s{seed}-{mode}-suffix-after.json')
        r['host_swap_mib_delta_primary']=[swap(b)-swap(a) for a,b in zip(before,after)]
        windows=[]
        for phase_ in ['prefix','suffix','replay']:
            a=read(f'snapshots/actual-s{seed}-{mode}-{phase_}-before.json');b=read(f'snapshots/actual-s{seed}-{mode}-{phase_}-after.json')
            for rank,(aa,bb) in enumerate(zip(a,b)):
                def counters(x):
                    line=next(l for l in x['iostat'].splitlines() if re.fullmatch(r'\s*[0-9.]+\s+[0-9]+\s+[0-9.]+\s*',l))
                    f=line.split();return int(f[1]),float(f[2])
                ac,am=counters(aa);bc,bm=counters(bb)
                assert bc>=ac and bm>=am
                windows.append({'rank':rank,'phase':phase_,'start_utc':aa['utc'],'end_utc':bb['utc'],'device_wide_reported_MB_delta':bm-am,'device_wide_transfer_count_delta':bc-ac,'host_swap_mib_delta':swap(bb)-swap(aa)})
        r['device_wide_io_windows']=windows
        checks=[read(f'phases/actual-s{seed}-{mode}-readback/rank{i}.json') for i in range(10)]
        check_sup=[read(f'phases/actual-s{seed}-{mode}-readback/rank{i}-supervisor.json') for i in range(10)]
        assert all(x['exit_status']==0 and x['child_reaped'] and not x['timed_out'] for x in check_sup)
        assert all(x['pass'] and x['sampler_states_exact'] and x['training_inputs_exact'] and x['boundary_ce_exact'] and x['optimizer_step_schedule_exact'] for x in checks)
        assert sum(x['owned_tensor_shards_per_generation'] for x in checks)==1216
        r['checkpoint_readback']={'files_per_generation':1216,'tensor_bytes_per_generation':[sum(x['file_bytes_per_generation'][i] for x in checks) for i in range(2)],'changed_shards_32_to_64':{k:sum(x['changed_shards_32_to_64'][k] for x in checks) for k in KINDS},'all_file_headers_hashes_global_seals_checked':True}
        conditions[f'{seed}-{mode}']=r

paired = summarize_pairs(
    [936, 937, 938], conditions, cohorts, all_streams,
    lambda seed, mode: group(seed, mode, 'prefix')[0],
)
summary = {
    'implementation_commit': '02a6ee4b',
    'schema': 'ced-moe-seed-replay-summary/v1',
    'completed_conditions': len(conditions),
    'completed_seed_pairs': paired['completed_seed_pairs'],
    'conditions': conditions,
    'pairs': paired['pairs'],
    'primary_delta_ram_minus_nvme': paired['primary_delta_ram_minus_nvme'],
    'secondary_delta_ram_minus_nvme': paired['secondary_delta_ram_minus_nvme'],
    'within_placement_replay_delta': {
        mode: descriptive([
            run['replay_minus_main_dev_ce']
            for run in conditions.values() if run['placement'] == mode
        ])
        for mode in ['ram', 'nvme']
    },
    'limits': [
        'descriptive n3; fixed-order2:1 not fullybalanced; no significance or equivalent-convergence claim',
        'same-checkpoint replay is not independent initialization/seed replication',
        '1Gbps shared hosts and retained inference; no coldcache/isolated latency claim',
        'extra dev sample is not independenttest; no selection on test',
        'all-in includes fingerprints and largerterminaldev diagnostics; excludes reporting gaps/readback/archive/replay',
        'logical I/O versus process OS versus device-wide counters are distinct; no exclusive wire/NAND count',
        'developer Mac executes stdlib only; model work fleet only',
    ],
}
summary['pooled_primary']={}
for mode in ['ram','nvme']:
    runs=[r for r in conditions.values() if r['placement']==mode]
    if not runs:continue
    tokens=sum(r['global_tokens_counted_once'] for r in runs)
    summary['pooled_primary'][mode]={'completed_seeds':len(runs),'primary_processed_tokens_counted_once':tokens,'all_in_tok_s':tokens/sum(r['all_in_supervisor_seconds'] for r in runs),'dev_inclusive_tok_s':tokens/sum(r['phase_dev_inclusive_seconds'] for r in runs),'update_only_tok_s':tokens/sum(r['update_seconds'] for r in runs),'max_footprint_gib':max(r['max_learner_footprint_gib'] for r in runs),'process_disk_read_bytes':sum(r['process_disk_read_bytes_all_ranks'] for r in runs),'process_disk_write_bytes':sum(r['process_disk_write_bytes_all_ranks'] for r in runs),'terminal_primary_dev_ce':descriptive([r['final_dev_ce'] for r in runs]),'terminal_secondary_dev_ce':descriptive([r['final_secondary_dev_ce'] for r in runs])}
write(HERE/'summary.json',summary)
print(json.dumps({'conditions':len(conditions),'pairs':summary['pairs'],'primary':summary['primary_delta_ram_minus_nvme'],'replay':summary['within_placement_replay_delta']},indent=2))
