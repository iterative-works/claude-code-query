# Implementation Tasks: Publish to Sonatype with separate ox and cats-effect artifacts

**Issue:** CC-12
**Created:** 2026-03-30
**Status:** 0/4 phases complete (0%)

## Phase Index

- [ ] Phase 01: Build Infrastructure (Est: 3-5h) → `phase-01-context.md`
- [ ] Phase 02: Source Reorganization (Est: 2-4h) → `phase-02-context.md`
- [ ] Phase 03: Publishing Configuration (Est: 2-4h) → `phase-03-context.md`
- [ ] Phase 04: Verification (Est: 1-2h) → `phase-04-context.md`

## Progress Tracker

**Completed:** 0/4 phases
**Estimated Total:** 8-15 hours
**Time Spent:** 0 hours

## Notes

- Phase context files generated just-in-time during implementation
- Use wf-implement to start next phase automatically
- Estimates are rough and will be refined during implementation
- Phases follow layer dependency order: build → source reorg → publishing → verification
- Phase 02 and 03 could partially overlap (publishing config is independent of file layout) but are sequenced for simplicity
- Key decisions resolved: Scala 3.3.7 LTS, delete model.scala + facade ClaudeCode.scala, PublishModule + SonatypeCentralPublishModule, no IWPublishModule
