# Implementation Tasks: Add Option-C tool-isolation knobs (strict MCP config, mcp-config path, setting sources, DontAsk permission mode)

**Issue:** CC-46
**Created:** 2026-04-21
**Status:** 0/1 phases complete (0%)

## Phase Index

- [ ] Phase 1: Option-C isolation knobs (domain + CLI + tests) (Est: 2.75-4.5h) → `phase-01-context.md`

## Progress Tracker

**Completed:** 0/1 phases
**Estimated Total:** 2.75-4.5 hours
**Time Spent:** 0 hours

## Notes

- Phase context files generated just-in-time during implementation
- Use wf-implement to start next phase automatically
- Estimates are rough and will be refined during implementation
- Phases follow layer dependency order (domain → infrastructure → application → presentation); a single phase may merge multiple layers when each alone is below the phase-size floor
- CC-46 is a cohesive single-phase change: the three layers (domain model, CLI translation, tests) are mechanically coupled via Scala 3 enum exhaustiveness, and the low-end (2.75h) is below the 3h phase-size floor, so merging is mandatory per the sizing policy
