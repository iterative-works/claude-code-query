# Implementation Tasks: Support persistent two-way conversations with a single Claude Code session

**Issue:** CC-15
**Created:** 2026-04-04
**Status:** 4/6 phases complete (67%)

## Phase Index

- [x] Phase 01: Stdin message format and response delimiting (Est: 6-8h) → `phase-01-context.md`
- [x] Phase 02: SessionOptions configuration (Est: 4-6h) → `phase-02-context.md`
- [x] Phase 03: Direct API - Basic session lifecycle (Est: 12-16h) → `phase-03-context.md`
- [x] Phase 04: Direct API - Multi-turn conversation (Est: 4-6h) → `phase-04-context.md`
- [ ] Phase 05: Effectful API - Session lifecycle with Resource (Est: 8-12h) → `phase-05-context.md`
- [ ] Phase 06: Error handling - process crash and malformed JSON (Est: 6-8h) → `phase-06-context.md`

## Progress Tracker

**Completed:** 4/6 phases
**Estimated Total:** 40-56 hours
**Time Spent:** 0 hours

## Notes

- Phase context files generated just-in-time during implementation
- Use ag-implement to start next phase automatically
- Estimates are rough and will be refined during implementation
- Phase 01 establishes the stream-json protocol types (SDKUserMessage, response delimiting via ResultMessage)
- Phases 01 and 02 can proceed in parallel (independent foundations)
- Phase 03 depends on Phases 01 and 02
- Phase 04 depends on Phase 03
- Phase 05 depends on Phases 01 and 02 (parallel to Phases 03-04)
- Phase 06 depends on Phases 03 and 05
