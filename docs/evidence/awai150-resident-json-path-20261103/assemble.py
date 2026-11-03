# Test-only unity source assembly; inputs are explicit, no services are started.
import argparse
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('repo', type=Path)
parser.add_argument('jcs_reader', type=Path)
parser.add_argument('output', type=Path)
args = parser.parse_args()
repo=args.repo
j=args.jcs_reader.read_text();reader=j[j.index('(defn- jr-at'):]
cp=(repo/'kotoba/git_checkpoint.kotoba').read_text()
en=(repo/'kotoba/fleet_node_enrollment.kotoba').read_text()
ar=(repo/'kotoba/github_checkpoint_archive.kotoba').read_text()
res=(repo/'kotoba/profile_resident.kotoba').read_text()
tests=(repo/'kotoba/profile_resident_test.kotoba').read_text()
line=en.index('(defn- en-bytes'); end=en.index('\n',line)
s='(ns path-test (:export [main]))\n'+reader+'\n'+en[line:end+1]+en[en.index('(defn field-problem'):en.index('(defn declaration-text')]
s+=cp[cp.index('(defn- cp-safe-absolute?'):cp.index(';; Intentionally bounded ASCII path profile.')]
s+=cp[cp.index('(defn- cp-str ['):cp.index('(defn- cp-num [')]
s+='(defn- cp-bytes [s :string] :vector-i64 (vector-i64-from-bytes (string-to-utf8 s)))\n'
s+=ar[ar.index('(defn- ga-path-overlap?'):ar.index(';; Never copy')]
s+=res[res.index('(defn- rt-ack-dir-safe?'):res.index(';; Archive an admitted head')]
s+=tests[tests.index('(defn test-profile-archive-path-safety'):]+ '\n(defn main [] :i64 (if (test-profile-archive-path-safety) 0 1))\n'
args.output.mkdir(parents=True,exist_ok=True)
(args.output/'fixture.kotoba').write_text(s)
needle='(rt-work-resident-safe? cfg work)'
assert s.count(needle)==1
(args.output/'mutant.kotoba').write_text(s.replace(needle,'true',1))
