"""Two fixed placements for a remaining seed, serial fleet jobs only."""
import sys
from run_condition import run
seed=int(sys.argv[1]);assert seed in [937,938]
for mode in ['nvme','ram']:run(seed,mode)
