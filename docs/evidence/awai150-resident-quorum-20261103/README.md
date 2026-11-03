# Resident lease path against three native groups

A disposable ARM64 Linux VM (1 CPU, 1 GiB) ran native static artifacts on
three distinct loopback addresses. The test used the current `rt-current-lease`
path, actual lease-group voter and proposer functions, an operator-signed
synthetic topology with the public RFC 8032 test key, and a synthetic HMAC
token. Each group had its own private task directory and durable local voter
files. The resident entry point was a test wrapper around the lease path; it
was not the complete resident service.

The resident obtained and saved an epoch-1 lease with three groups; with one
group stopped it obtained the same lease from two. With only one group it
returned no lease and did not change its saved file. Restarting a second group
against its existing state allowed the same lease again. Each test invocation
was a new resident process.

A separate upgrade test initialized three fresh groups with the previous
`valid-value?` and used the native proposer to choose expiry `-1`. After
restarting the same task-owned groups with the updated validation, the current
resident returned no lease; no successor epoch file or local lease was created,
and the epoch watermarks remained unchanged. This is a fail-closed outcome:
the invalid prior value needs explicit reconciliation. It does not demonstrate
successful takeover of that value or authorize it.

All group processes belonged to the fixture, and the VM was stopped and deleted.
The three groups shared one VM, filesystem, network stack and clock. The test
was not a Mac, independent failure-domain, encrypted transport, production
credential, inference, checkpoint, cron, scheduler or autonomous bot test.
The scripts are test-only, use fixed paths, and require adaptation to rerun.

The source-unity generator combines current lease, group, resident and related
functions with the historical dependency unity noted in
[the controller evidence](../awai150-current-cron-unity-20261103/README.md).
It does not certify every dependency against current main. Source SHA-256:
group `b0831102692ef7ebf2f3135734cf2a04c61ad60abfbd1ed6a2c2f6f661d21fed`;
resident `b2b953cd8e5194350ab63417f3217f368e4e4eab71b34d9f7c7b2ed7174e8517`;
old-group `9b66f85c4f9e0a549d8a21dff8f20c346ff7dd9ab9e459f857ecc4d07ae3fa9c`;
seed-bad `a056021d1acc27d7d56c313c6b4b82d5882c886040111af22af2cd28cf0f9973`.
Compiler `914b828a24ced086e09929894bb64806dea103bf79766ef144001a076717b1c4`;
static ELFs: group `4973215be461d014c6344f15f14f7de6ccac88edc7f6edc4e6e193c24c866be3`;
resident `0dcc72dcec4b46aa69532d20575b3cc6fe60601bebafe24488ff67faa7a7c38c`;
old-group `ab00fa2575b391f96b4817ed8d919df0b93d86301c675869af60f53c0bfef74c`;
seed-bad `dc0e513e8ac4f46a8bc11d5037963eb58694be6b2b8a014dfbc67571a71a5fd0`.
