# Phase 03: Support entries without uuid field

## Defect Description

`ConversationLogParser.parseLogEntry` requires a `uuid` field in its for-comprehension (line 36-37 of `ConversationLogParser.scala`):

```scala
for
  uuid <- cursor.get[String]("uuid").toOption
```

Entry types such as `permission-mode` and `file-history-snapshot` do not carry a `uuid` field in the Claude Code JSON format. Because the for-comprehension short-circuits on `None`, these entries are silently dropped before payload parsing is even attempted. This means entire categories of log entries are invisible to consumers regardless of whether their type name is correctly matched.

## Reproduction Steps

1. Obtain a Claude Code transcript containing a `permission-mode` or `file-history-snapshot` entry (these lack a `uuid` field)
2. Parse the transcript using `ConversationLogParser.parseConversationLog`
3. Observe that entries without `uuid` are absent from the parsed result
4. Confirm the entries exist in the raw JSON but are filtered out by the `uuid <- ...toOption` binding

## Root Cause Hypotheses

### H1: Parser assumes all entries carry a uuid field (Confidence: High)

The for-comprehension in `parseLogEntry` uses monadic binding (`<-`) for the `uuid` extraction. When `cursor.get[String]("uuid")` fails (because the field is absent), `.toOption` yields `None`, and the entire for-comprehension short-circuits to `None`. The parser was written under the assumption that every entry has a `uuid`, but the Claude Code format does not guarantee this for all entry types.

## Fix Strategy

Make `uuid` optional in `ConversationLogEntry` (change from `String` to `Option[String]`). In the for-comprehension, replace the monadic bind:

```scala
// Before
uuid <- cursor.get[String]("uuid").toOption

// After
uuid = cursor.get[String]("uuid").toOption
```

This converts `uuid` from a filter condition into a plain assignment, so entries without `uuid` pass through with `None` instead of being dropped. Downstream consumers of `ConversationLogEntry` must then handle `Option[String]` for the `uuid` field.

Propagate the type change through any code that references `ConversationLogEntry.uuid` (model classes, tests, downstream consumers).

## Previous Phase Fixes

- **Phase 01** fixed entry type name mismatches (`"human"` to `"user"`, underscore to hyphen for compound types) and added `permission-mode` and `attachment` entry type support. Without Phase 03, `permission-mode` entries are still dropped despite their type name now being recognized, because they lack `uuid`.
- **Phase 02** made `agentId` derived from the transcript filename rather than requiring it in the JSON. This removed one source of silent entry dropping; Phase 03 removes another.

## Testing Requirements

- **Unit test**: Parse a JSON entry that has no `uuid` field and verify it produces a `ConversationLogEntry` with `uuid = None`
- **Unit test**: Parse a JSON entry that has a `uuid` field and verify it produces a `ConversationLogEntry` with `uuid = Some(value)`
- **Unit test**: Parse a transcript containing a mix of entries with and without `uuid` and verify all entries are present in the result
- **Regression test**: Verify that existing entry types (`user`, `assistant`, `tool-use`, `tool-result`) that do carry `uuid` still parse correctly with `Some(uuid)`

## Acceptance Criteria

- `ConversationLogEntry.uuid` has type `Option[String]`
- Entries without a `uuid` field in the JSON are parsed successfully (not silently dropped)
- Entries with a `uuid` field still parse correctly, with the value wrapped in `Some`
- All existing tests pass (updated to expect `Option[String]` where needed)
- New tests cover both the `uuid`-present and `uuid`-absent cases
