# Database schema fixtures

`dev.fieldnotes.data.FieldNotesDatabase/3.json` is exported by Room's KSP compiler.
Commit this file whenever the schema changes; never edit an exported current schema.

`1.json` is an instructional baseline fixture: the same tables without the
`notes.updatedAt` index. It is not a claim that a version 1 app was released.
Its fixture identity is independent of the current compiler's identity hash.
`DatabaseMigrationTest` creates this baseline, inserts an unsynchronized note and
outbox operation, runs `MIGRATION_1_2`, and asks Room to validate the complete new
schema while checking that the pending edit remains intact. Version 2 is retained
as a historical compiler export. The version 2-to-3 test preserves an unacknowledged
simulator replay receipt and its queued operation, removes an acknowledged orphan,
and validates the sync-state index and durable fairness cursor. A receipt is eligible
for pruning only when its operation ID is absent from the durable outbox.

Production migrations need fixtures from each actually released schema and
upgrade tests covering every supported path, including realistic database sizes.
