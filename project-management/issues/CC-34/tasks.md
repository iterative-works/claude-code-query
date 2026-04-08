# Implementation Tasks: Resolve Claude projects directory inside the lib (honor CLAUDE_CONFIG_DIR)

**Issue:** CC-34
**Created:** 2026-04-08
**Status:** 0/3 phases complete (0%)

## Phase Index

- [ ] Phase 1: Core — `ProjectPathEncoder` + `ClaudeProjects` (Est: 1.5-3h) → `phase-01-context.md`
- [ ] Phase 2: Direct — cwd-based conveniences on `DirectConversationLogIndex` (Est: 0.5-1h) → `phase-02-context.md`
- [ ] Phase 3: Effectful — IO-wrapped conveniences on `EffectfulConversationLogIndex` (Est: 0.5-1h) → `phase-03-context.md`

## Progress Tracker

**Completed:** 0/3 phases
**Estimated Total:** 2.5-5 hours
**Time Spent:** 0 hours

## Notes

- Phase context files generated just-in-time during implementation
- Use wf-implement to start next phase automatically
- Phases 2 and 3 are independent and can run in parallel after Phase 1
- Existing path-based API must remain source-compatible
