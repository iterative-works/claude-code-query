# Technical Analysis: Publish to Sonatype with separate ox and cats-effect artifacts

**Issue:** CC-12
**Created:** 2026-03-30
**Status:** Draft

## Problem Statement

The library currently ships as a single flat artifact built with Scala CLI. Users who only need the direct-style (Ox) API are forced to pull in cats-effect, fs2, and log4cats as transitive dependencies, and vice versa. This bloats dependency trees and can cause version conflicts in downstream projects.

Additionally, the project cannot be published to Maven Central because Scala CLI's publishing support is limited and the project lacks the multi-module structure needed for proper artifact separation.

## Proposed Solution

### High-Level Approach

Migrate the build from Scala CLI to Mill, using the existing `mill-iw-support` plugin for standardized module configuration. The project will be split into three Mill modules that mirror the existing source directory structure: `core`, `direct`, and `effectful`. Each module produces an independent artifact with only its own dependencies.

Publishing will target Sonatype / Maven Central by overriding the `IWPublishModule` repository URIs (which default to e-BS Nexus) and enabling GPG signing. The `IWPublishModule` already supports URI overrides via environment variables or method overrides, so this is a configuration concern rather than a code change.

The existing source layout already maps cleanly to the three-module split. The top-level `ClaudeCode.scala` (effectful facade) and `model.scala` (legacy duplicate types) need to be dealt with -- `ClaudeCode.scala` belongs in the effectful module and `model.scala` is a duplicate of `core.model` types that should be removed or turned into re-exports.

### Why This Approach

Mill is the standard build tool for Iterative Works projects and `mill-iw-support` provides ready-made traits for module configuration, compiler options, and publishing. Scala CLI lacks native multi-module support, making it unsuitable for artifact separation. The existing source structure already groups code by module boundary, minimizing source file moves.

## Architecture Design

**Purpose:** Define WHAT components each layer needs, not HOW they're implemented.

Note: This issue is a build/packaging concern, not a feature. The "layers" here map to build modules and publishing infrastructure rather than traditional domain/application/infrastructure/presentation layers.

### Build Infrastructure Layer

**Components:**
- `build.mill` -- root build definition with three sub-modules
- `core` Mill module (`IWScalaModule` + `IWPublishModule`)
- `direct` Mill module (`IWScalaModule` + `IWPublishModule`, depends on `core`)
- `effectful` Mill module (`IWScalaModule` + `IWPublishModule`, depends on `core`)
- Shared `pomSettings` and `publishVersion` definitions
- `.mill-version` file specifying Mill version

**Responsibilities:**
- Module dependency graph: `direct -> core`, `effectful -> core`
- Per-module dependency declarations (only the deps each module needs)
- Test module configuration per sub-module
- Sonatype publishing configuration (URI overrides, GPG signing)
- Scala version pinning (currently 3.7.4 in project.scala; `IWScalaVersions` defaults to 3.8.1 -- needs decision)

**Estimated Effort:** 3-5 hours
**Complexity:** Moderate

---

### Source Reorganization Layer

**Components:**
- Move source files into Mill's conventional layout (`core/src/`, `direct/src/`, `effectful/src/`)
- Move test files into Mill's test layout (`core/test/src/`, `direct/test/src/`, `effectful/test/src/`)
- Move test resources (mock scripts in `test/bin/`) to appropriate test module resources
- Relocate `logback.xml` resource to appropriate module(s)
- Handle top-level `ClaudeCode.scala` (effectful facade) and `model.scala` (duplicate types)

**Responsibilities:**
- Ensure each module compiles independently with correct visibility
- Ensure no circular dependencies between modules
- Resolve the `model.scala` duplication (top-level `works.iterative.claude` package duplicates `core.model` types)
- Place the top-level `ClaudeCode.scala` facade in the effectful module (it imports cats-effect/fs2)
- Move shared test utilities to appropriate test scopes
- Ensure mock CLI scripts are accessible from test modules that need them

**Estimated Effort:** 2-4 hours
**Complexity:** Moderate

---

### Publishing Configuration Layer

**Components:**
- Sonatype Central repository URI configuration (override `IWPublishModule` defaults)
- GPG signing setup for Maven Central compliance
- POM metadata (groupId `works.iterative.claude`, per-module artifactIds)
- Version management (0.1.0 release)
- CI/CD publishing workflow (if applicable)

**Responsibilities:**
- Correct POM generation with proper dependency scoping
- Each published POM must declare only its own transitive dependencies
- GPG signing of artifacts for Maven Central acceptance
- Sonatype staging and release process
- Credential management via environment variables

**Estimated Effort:** 2-4 hours
**Complexity:** Moderate

---

### Verification Layer

**Components:**
- POM dependency tree verification (no cross-contamination of deps)
- Local publish and inspection (`mill __.publishLocal`)
- Compilation verification for each module in isolation
- Test execution across all modules
- Transitive dependency analysis

**Responsibilities:**
- Verify `core` POM only pulls circe
- Verify `direct` POM pulls core + os-lib + ox (no cats-effect)
- Verify `effectful` POM pulls core + cats-effect + fs2 + log4cats (no ox)
- Verify all existing tests pass under the new build structure
- Verify published artifacts are resolvable from Maven Central after release

**Estimated Effort:** 1-2 hours
**Complexity:** Straightforward

---

## Technical Decisions

### Patterns

- Mill multi-module project with shared trait for common publish settings
- `IWScalaModule` + `IWPublishModule` per sub-module
- No BOM module needed (this is a library, not an application; consumers manage their own versions)

### Technology Choices

- **Build Tool**: Mill (replacing Scala CLI)
- **Build Plugin**: `mill-iw-support` (IWScalaModule, IWPublishModule)
- **Publishing Target**: Sonatype Central / Maven Central
- **Signing**: GPG (required for Maven Central)

### Integration Points

- `direct` module depends on `core` module (compile scope)
- `effectful` module depends on `core` module (compile scope)
- `IWPublishModule` URIs overridden to point to Sonatype instead of e-BS Nexus
- Test modules may share mock CLI scripts (need to determine if these go in a shared test resource or are duplicated)

## Resolved Decisions

### Scala Version: 3.3.7 LTS

Both Ox 1.0.4 and cats-effect 3.7.0 are built against Scala 3.3.7 (LTS). For a published library, targeting LTS maximizes downstream compatibility. Override `scalaVersion` in the build to `3.3.7` (do not use `IWScalaVersions` default of 3.8.1).

### Top-Level model.scala and ClaudeCode.scala: Delete Both

- `model.scala` duplicates types already in `core/model/`. Nothing imports from it.
- `ClaudeCode.scala` is a facade delegating to `effectful.ClaudeCode`. Nothing imports from it.
- Pre-1.0 library, no published consumers. Clean deletion, no backward compat needed.

### Publishing: PublishModule + SonatypeCentralPublishModule

Do not use `IWPublishModule` (it defaults to e-BS Nexus with signing disabled). Instead:
- Each module extends Mill's built-in `PublishModule` directly (following `scalatags-web-awesome` pattern).
- Publish via `mill mill.javalib.SonatypeCentralPublishModule/publishAll` which handles GPG signing, staging, and release.
- Credentials via `MILL_SONATYPE_USERNAME`/`MILL_SONATYPE_PASSWORD` and `MILL_PGP_SECRET_BASE64`/`MILL_PGP_PASSPHRASE` env vars.

### Logging Dependencies: Per-Module, No Logging in Core

- Core module has zero logging imports — no logging deps needed.
- Direct module brings its own SLF4J-based `Logger.scala`.
- Effectful module uses `log4cats-slf4j`.
- `logback-classic` is test-scoped only (runtime backend, not API).

### Test Mock Scripts: No Sharing Needed

- `MockCliScript.scala` (in `direct/internal/testing/`) — used only by direct module tests. Stays with direct.
- `test/bin/mock-claude*` shell scripts — used only by top-level integration tests that import `effectful.ClaudeCode`. These tests and scripts move to the effectful module's test scope.
- Effectful unit tests create inline mocks, no script dependency.
- No shared test module needed.

## Technical Risks

---

## Total Estimates

**Per-Layer Breakdown:**
- Build Infrastructure: 3-5 hours
- Source Reorganization: 2-4 hours
- Publishing Configuration: 2-4 hours
- Verification: 1-2 hours

**Total Range:** 8 - 15 hours

**Confidence:** Medium

**Reasoning:**
- The source layout already matches the target module structure, reducing reorganization risk
- `mill-iw-support` provides most of the build infrastructure, but Sonatype-specific config needs work
- CLARIFY items (Scala version, model.scala, signing) could add time if decisions require iteration
- Mill multi-module builds are well-documented but first-time setup for this project
- The actual source code should not need functional changes, only file moves and package adjustments

## Testing Strategy

### Per-Layer Testing

Each layer should have appropriate test coverage:

**Build Infrastructure:**
- Verify each module compiles independently: `mill core.compile`, `mill direct.compile`, `mill effectful.compile`
- Verify tests run per module: `mill __.test`

**Source Reorganization:**
- All existing tests must pass under the new layout without modification (beyond import path changes if packages move)
- Verify no compilation errors from missing dependencies in any module

**Publishing Configuration:**
- `mill __.publishLocal` succeeds for all modules
- Inspect generated POMs in `~/.ivy2/local/` or `out/` for correct dependency trees
- Verify GPG signing works with `mill __.publish --signed`

**Verification:**
- Dependency tree inspection: `mill core.ivyDepsTree`, `mill direct.ivyDepsTree`, `mill effectful.ivyDepsTree`
- Confirm `direct` has no cats-effect/fs2 in its tree
- Confirm `effectful` has no ox/os-lib in its tree
- Confirm `core` has neither ox nor cats-effect

**Test Data Strategy:**
- Existing mock CLI scripts and test fixtures carry over
- No new test data needed

**Regression Coverage:**
- All existing unit tests must pass
- All existing integration tests must pass
- The behavior of the library is unchanged; only packaging changes

## Deployment Considerations

### Database Changes
None.

### Configuration Changes
- New `build.mill` replaces `project.scala` and `publish-conf.scala`
- `.mill-version` file added
- Environment variables for Sonatype credentials: `MILL_SONATYPE_USERNAME`, `MILL_SONATYPE_PASSWORD`
- GPG key must be available for signing

### Rollout Strategy
1. Merge build migration to main
2. Tag `v0.1.0`
3. Publish to Sonatype staging
4. Verify staging artifacts
5. Release from staging to Maven Central

### Rollback Plan
- If published artifacts have issues, Sonatype staging can be dropped before release
- Once released to Maven Central, artifacts are immutable; would need a 0.1.1 fix release

## Dependencies

### Prerequisites
- Mill installed in development environment
- `mill-iw-support` published and available (currently at 0.1.4-SNAPSHOT -- needs to be a release or available from a snapshot repo)
- Sonatype account with `works.iterative` group ID ownership
- GPG key for artifact signing

### Layer Dependencies
- Source Reorganization depends on Build Infrastructure (need `build.mill` to compile against)
- Publishing Configuration depends on Build Infrastructure
- Verification depends on all other layers

### External Blockers
- Sonatype group ID `works.iterative.claude` may need to be registered/verified (if `works.iterative` is already verified, subgroups should work)
- `mill-iw-support` availability -- if only published as SNAPSHOT to e-BS Nexus, the build needs access to that repository or the plugin needs a release

## Risks & Mitigations

### Risk 1: mill-iw-support Availability
**Likelihood:** Medium
**Impact:** High
**Mitigation:** Verify `mill-iw-support` is accessible before starting. If only on e-BS Nexus as SNAPSHOT, either publish a release or configure Mill to resolve from that repo.

### Risk 2: Scala Version Incompatibility
**Likelihood:** Low
**Impact:** Medium
**Mitigation:** Test compilation on both 3.7.4 and 3.8.1 early. Ox and cats-effect should work on both.

### Risk 3: Test Migration Breakage
**Likelihood:** Medium
**Impact:** Low
**Mitigation:** Mock CLI scripts rely on filesystem paths. Mill's test working directory differs from Scala CLI's. Tests referencing `test/bin/` by relative path will need adjustment.

### Risk 4: Sonatype/Maven Central Rejection
**Likelihood:** Low
**Impact:** Medium
**Mitigation:** Follow Maven Central requirements checklist (POM completeness, javadoc/sources jars, GPG signing). Mill's `PublishModule` handles most of this. Validate with `publishLocal` before attempting remote publish.

### Risk 5: Duplicate Types in model.scala
**Likelihood:** High (it exists now)
**Impact:** Low
**Mitigation:** Resolve during source reorganization. For 0.1.0, simply removing the duplicate is the cleanest path since there are no existing Maven Central consumers.

---

## Implementation Sequence

**Recommended Layer Order:**

1. **Build Infrastructure** -- Create `build.mill` with three modules and correct dependency declarations. This is the foundation everything else builds on.
2. **Source Reorganization** -- Move source and test files into Mill's directory layout. Resolve `model.scala` and top-level `ClaudeCode.scala`. Verify compilation.
3. **Publishing Configuration** -- Configure Sonatype URIs, GPG signing, POM metadata. Test with `publishLocal`.
4. **Verification** -- Inspect POMs, run full test suite, verify dependency trees. Publish 0.1.0 when ready.

**Ordering Rationale:**
- Build infrastructure must exist before sources can be compiled
- Source reorganization must be done before publishing makes sense
- Publishing config can be partially parallelized with source reorg (POM settings are independent of file layout)
- Verification is inherently last

## Documentation Requirements

- [ ] Code documentation (inline comments for complex logic) -- minimal, this is mostly build config
- [ ] API documentation (if adding/changing APIs) -- no API changes
- [ ] Architecture decision record (if significant pattern/technology choice) -- update ARCHITECTURE.md to reflect multi-module structure
- [ ] User-facing documentation (if UI changes) -- update README.md with Maven coordinates for each artifact
- [ ] Migration guide (if breaking changes) -- document new import paths if `model.scala` is removed

---

**Analysis Status:** Ready for Review

**Next Steps:**
1. Resolve CLARIFY markers with stakeholders
2. Run **wf-create-tasks** with the issue ID
3. Run **wf-implement** for layer-by-layer implementation
