"""Summarize verified RAM/NVMe conditions without reading receipts."""

import statistics


def gradient_changes(a, b):
    return sum(a[n] != b[n] for n in a)


def descriptive(values):
    if not values:
        return None
    return {
        'n': len(values),
        'values': values,
        'mean': statistics.mean(values),
        'sample_sd': statistics.stdev(values) if len(values) > 1 else None,
        'min': min(values),
        'max': max(values),
        'positive': sum(x > 0 for x in values),
        'zero': sum(x == 0 for x in values),
        'negative': sum(x < 0 for x in values),
    }


def summarize_pairs(seeds, conditions, cohorts, streams, load_prefix):
    pairs = []
    for seed in seeds:
        ram_key, nvme_key = f'{seed}-ram', f'{seed}-nvme'
        if ram_key not in conditions or nvme_key not in conditions:
            continue
        assert cohorts[seed, 'ram'] == cohorts[seed, 'nvme']
        assert streams[seed, 'ram'] == streams[seed, 'nvme']
        ram, nvme = conditions[ram_key], conditions[nvme_key]
        prefix_rows = list(zip(load_prefix(seed, 'ram'), load_prefix(seed, 'nvme')))
        assert all(a['initial_state_sha256'] == b['initial_state_sha256'] for a, b in prefix_rows)
        pairs.append({
            'seed': seed,
            'same_initial_states_stream_cohort_except_placement': True,
            'prefix_preoptimizer_gradient_changed_shards': sum(
                gradient_changes(
                    a['trace'][0]['first_update_gradient_sha256'],
                    b['trace'][0]['first_update_gradient_sha256'],
                )
                for a, b in prefix_rows
            ),
            'ram_minus_nvme_initial_ce': ram['initial_dev_ce'] - nvme['initial_dev_ce'],
            'ram_minus_nvme_step32_ce': ram['step32_dev_ce'] - nvme['step32_dev_ce'],
            'ram_minus_nvme_final64_ce': ram['final_dev_ce'] - nvme['final_dev_ce'],
            'ram_minus_nvme_secondary64_ce': ram['final_secondary_dev_ce'] - nvme['final_secondary_dev_ce'],
            'ram_to_nvme_all_in_speed_ratio': ram['all_in_tok_s'] / nvme['all_in_tok_s'],
            'ram_to_nvme_dev_inclusive_speed_ratio': ram['phase_dev_inclusive_tok_s'] / nvme['phase_dev_inclusive_tok_s'],
        })
    return {
        'completed_seed_pairs': len(pairs),
        'pairs': pairs,
        'primary_delta_ram_minus_nvme': descriptive([p['ram_minus_nvme_final64_ce'] for p in pairs]),
        'secondary_delta_ram_minus_nvme': descriptive([p['ram_minus_nvme_secondary64_ce'] for p in pairs]),
    }
