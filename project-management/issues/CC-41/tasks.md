# Investigation Tasks: Parser mismatches: wrong entry type names, SubAgentMetadataParser requires missing field

**Issue:** CC-41
**Created:** 2026-04-10
**Severity:** High
**Status:** 1/3 phases complete (33%)

## Phase Index

- [x] Phase 01: Fix entry type name mismatches in ConversationLogParser (Est: 1-2h) → `phase-01-context.md`
- [ ] Phase 02: Derive agentId from filename in SubAgentMetadataParser (Est: 1h) → `phase-02-context.md`
- [ ] Phase 03: Support entries without uuid field (Est: 1-2h) → `phase-03-context.md`

## Progress Tracker

**Completed:** 1/3 phases
**Estimated Total:** 3-5 hours
**Time Spent:** 0 hours

## Notes

- Phase context files generated just-in-time during investigation
- Use dx-fix to start next phase automatically
- Estimates are rough and will be refined during investigation
- Each phase follows: reproduce → investigate → fix → verify
- Phases ordered by impact: user entry parsing (highest) → sub-agent metadata → uuid handling
