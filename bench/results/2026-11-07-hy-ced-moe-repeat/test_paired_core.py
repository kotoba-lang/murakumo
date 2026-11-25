import unittest

from paired_core import summarize_pairs


class PairedSummaryTest(unittest.TestCase):
    def test_only_seed_with_both_completed_arms_contributes(self):
        ram = {
            'initial_dev_ce': 10.0,
            'step32_dev_ce': 8.0,
            'final_dev_ce': 7.0,
            'final_secondary_dev_ce': 8.5,
            'all_in_tok_s': 8.0,
            'phase_dev_inclusive_tok_s': 12.0,
        }
        nvme = {
            'initial_dev_ce': 10.0,
            'step32_dev_ce': 8.5,
            'final_dev_ce': 8.0,
            'final_secondary_dev_ce': 8.0,
            'all_in_tok_s': 4.0,
            'phase_dev_inclusive_tok_s': 8.0,
        }
        conditions = {
            '936-ram': ram,
            '936-nvme': nvme,
            '937-ram': {**ram, 'final_dev_ce': 40.0, 'final_secondary_dev_ce': 41.0},
            '938-nvme': {**nvme, 'final_dev_ce': 50.0, 'final_secondary_dev_ce': 51.0},
        }
        cohorts = {(936, 'ram'): 'shared cohort', (936, 'nvme'): 'shared cohort'}
        streams = {(936, 'ram'): ['same input'], (936, 'nvme'): ['same input']}
        prefix_calls = []

        def load_prefix(seed, mode):
            prefix_calls.append((seed, mode))
            return [{
                'initial_state_sha256': 'same initial state',
                'trace': [{'first_update_gradient_sha256': {'shard': mode}}],
            }]

        result = summarize_pairs([936, 937, 938], conditions, cohorts, streams, load_prefix)

        self.assertEqual(prefix_calls, [(936, 'ram'), (936, 'nvme')])
        self.assertEqual(result['completed_seed_pairs'], 1)
        self.assertEqual(result['pairs'], [{
            'seed': 936,
            'same_initial_states_stream_cohort_except_placement': True,
            'prefix_preoptimizer_gradient_changed_shards': 1,
            'ram_minus_nvme_initial_ce': 0.0,
            'ram_minus_nvme_step32_ce': -0.5,
            'ram_minus_nvme_final64_ce': -1.0,
            'ram_minus_nvme_secondary64_ce': 0.5,
            'ram_to_nvme_all_in_speed_ratio': 2.0,
            'ram_to_nvme_dev_inclusive_speed_ratio': 1.5,
        }])
        for metric, expected in [('primary', -1.0), ('secondary', 0.5)]:
            with self.subTest(metric=metric):
                self.assertEqual(result[f'{metric}_delta_ram_minus_nvme'], {
                    'n': 1, 'values': [expected], 'mean': expected, 'sample_sd': None,
                    'min': expected, 'max': expected, 'positive': int(expected > 0),
                    'zero': 0, 'negative': int(expected < 0),
                })
