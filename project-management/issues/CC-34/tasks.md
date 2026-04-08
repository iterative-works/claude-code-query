# Implementation Tasks: Resolve Claude projects directory inside the lib (honor CLAUDE_CONFIG_DIR)

**Issue:** CC-34
**Created:** 2026-04-08
**Status:** 1/1 phases complete (100%)

## Phase Index

- [x] Phase 1: Lib-owned project dir resolution (Est: 2.5-5h) → `phase-01-context.md`
  - `core/ProjectPathEncoder` + tests
  - `core/ClaudeProjects` (pure, parameterized env/home) + tests
  - `DirectConversationLogIndex.listSessionsFor(cwd)` / `forSessionAt(cwd, id)` + tests
  - `EffectfulConversationLogIndex` IO-wrapped equivalents + tests
  - Existing path-based API untouched (source-compatible)

## Progress Tracker

**Completed:** 1/1 phases
**Estimated Total:** 2.5-5 hours
**Time Spent:** 0 hours

## Notes

- Single phase — scope is small enough that layered ceremony is overkill
- Phase context generated just-in-time via wf-implement
