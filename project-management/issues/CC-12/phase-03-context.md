# Phase 03: Publishing Configuration

## Goals

Configure the project for publishing to Sonatype Central / Maven Central. This includes correct POM metadata with per-module artifactIds, GPG signing, credential management via environment variables, and a CI/CD workflow for automated releases on tag push.

After this phase, `mill __.publishLocal` produces correct artifacts and `mill mill.javalib.SonatypeCentralPublishModule/publishAll` is the documented publish command.

## Scope

### In Scope
- Verify and correct POM metadata in `SharedModule` (groupId, per-module artifactIds, developer info)
- Set per-module `artifactName` overrides: `claude-code-query-core`, `claude-code-query-direct`, `claude-code-query-effectful`
- Bump `publishVersion` from `0.1.0-SNAPSHOT` to `0.1.0` for release
- Document the `SonatypeCentralPublishModule` publishing command and required environment variables
- Create a GitHub Actions workflow for publishing on tag push (optional but recommended)
- Verify `publishLocal` produces correct POMs with proper dependency scoping

### Out of Scope
- Dependency tree verification (Phase 04)
- Functional code changes (no source changes to library code)
- Replacing `PublishModule` with `IWPublishModule` (decision: use `PublishModule` directly, per analysis)
- Actual publishing to Maven Central (that happens after all phases are verified)

## Dependencies

### Prior Phases
- **Phase 1 (Build Infrastructure)**: `build.mill` exists with three modules extending `SharedModule` which mixes in `PublishModule`. Completed.
- **Phase 2 (Source Reorganization)**: All sources in Mill layout, all tests passing. Completed.

### External Dependencies
- Mill 1.1.2 installed and functional
- `mill-iw-support` 0.1.4-SNAPSHOT accessible (published locally or from snapshot repo)
- Sonatype account with `works.iterative` group ID ownership
- GPG key for artifact signing (for actual publishing; not needed for `publishLocal` verification)

## Approach

### Step 1: Set per-module artifactNames

Override `artifactName` in each module so the published artifacts are:
- `claude-code-query-core_3`
- `claude-code-query-direct_3`
- `claude-code-query-effectful_3`

By default, Mill uses the module object name as the artifact name (i.e., `core`, `direct`, `effectful`). These need the `claude-code-query-` prefix for clarity on Maven Central.

In `build.mill`, either:
- Add `def artifactName = "claude-code-query-" + super.artifactName()` to `SharedModule`, or
- Override `artifactName` in each module individually.

The `SharedModule` approach is preferred since all modules follow the same pattern.

### Step 2: Verify POM metadata

The current `pomSettings` in `SharedModule` already contains:
- `organization = "works.iterative"` (groupId)
- `description`, `url`, `licenses`, `versionControl`, `developers`

Verify that the `description` field should be per-module (e.g., "Core types and parsing for Claude Code Scala SDK") or if the shared description is sufficient. For a first release, the shared description is acceptable.

### Step 3: Version management

Change `publishVersion` from `0.1.0-SNAPSHOT` to `0.1.0` for the release. This should be done as the last commit before tagging, or can be parameterized to read from an environment variable or git tag.

Consider whether to make `publishVersion` dynamic (e.g., read from a file or git tag). For simplicity, a hardcoded version in `build.mill` is fine for now.

### Step 4: Verify publishLocal

Run `mill __.publishLocal` and inspect the generated artifacts:
- Check `~/.ivy2/local/works.iterative/claude-code-query-core_3/0.1.0/` exists
- Inspect the POM files to verify:
  - Correct groupId (`works.iterative`)
  - Correct artifactId (`claude-code-query-core_3`, etc.)
  - Correct dependencies (core has only circe, direct has core + os-lib + ox + slf4j, effectful has core + cats-effect + fs2 + log4cats)
  - No cross-contamination (direct POM has no cats-effect, effectful POM has no ox)

### Step 5: Document publishing workflow

The publishing command is:
```bash
mill mill.javalib.SonatypeCentralPublishModule/publishAll
```

Required environment variables:
- `MILL_SONATYPE_USERNAME` — Sonatype Central username
- `MILL_SONATYPE_PASSWORD` — Sonatype Central password/token
- `MILL_PGP_SECRET_BASE64` — base64-encoded GPG secret key
- `MILL_PGP_PASSPHRASE` — GPG key passphrase

### Step 6: GitHub Actions workflow (optional)

Create `.github/workflows/publish.yml` that:
1. Triggers on tag push matching `v*`
2. Sets up JVM and Mill
3. Runs `mill __.test` as a gate
4. Runs the `SonatypeCentralPublishModule/publishAll` command
5. Uses repository secrets for Sonatype and GPG credentials

## Files to Create/Modify

### Files to Modify
| File | Change |
|------|--------|
| `build.mill` | Add `artifactName` override in `SharedModule`; bump `publishVersion` to `0.1.0` |

### Files to Create
| File | Purpose |
|------|---------|
| `.github/workflows/publish.yml` | CI/CD workflow for publishing on tag push (optional) |

## Testing Strategy

### Verification Steps

1. **publishLocal**: `mill __.publishLocal` succeeds without errors
2. **POM inspection**: Generated POMs have correct groupId, artifactId, version, and dependency declarations
3. **Artifact naming**: Published artifacts follow the `claude-code-query-{module}_3` pattern
4. **Dependency isolation**: core POM has no ox/cats-effect deps; direct POM has no cats-effect deps; effectful POM has no ox deps
5. **All tests still pass**: `mill __.test` remains green (no regressions from build.mill changes)

### Smoke Tests

```bash
mill __.publishLocal
# Inspect ~/.ivy2/local/works.iterative/claude-code-query-core_3/0.1.0/poms/
# Inspect ~/.ivy2/local/works.iterative/claude-code-query-direct_3/0.1.0/poms/
# Inspect ~/.ivy2/local/works.iterative/claude-code-query-effectful_3/0.1.0/poms/
```

## Acceptance Criteria

- [ ] `SharedModule` has `artifactName` override producing `claude-code-query-{module}` names
- [ ] `publishVersion` is set to `0.1.0`
- [ ] `mill __.publishLocal` succeeds for all three modules
- [ ] Generated POM for `core` declares only circe dependencies
- [ ] Generated POM for `direct` declares core + os-lib + ox + slf4j (no cats-effect)
- [ ] Generated POM for `effectful` declares core + cats-effect + fs2 + log4cats (no ox)
- [ ] Artifact coordinates are `works.iterative:claude-code-query-{core,direct,effectful}_3:0.1.0`
- [ ] `mill __.test` passes (no regressions)
- [ ] Publishing command and required env vars are documented (in workflow file or README)
- [ ] GitHub Actions publish workflow exists (if included in scope)
