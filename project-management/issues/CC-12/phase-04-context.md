# Phase 04: Verification

## Goals

Perform final verification of the build migration: formal dependency tree inspection, documentation updates to reflect the new multi-module structure and Maven coordinates, cleanup of any leftover Scala CLI artifacts, and validation of the GitHub Actions publish workflow.

After this phase, the project is ready for tagging `v0.1.0` and publishing to Maven Central.

## Scope

### In Scope
- Formal dependency tree inspection using `mill {module}.ivyDepsTree` for all three modules
- Confirm no cross-contamination: `direct` has no cats-effect/fs2, `effectful` has no ox, `core` has neither
- Update `ARCHITECTURE.md` to reflect the Mill multi-module structure (build tool, module layout, dependency graph)
- Update `README.md` with Maven Central coordinates for all three modules and remove Scala CLI references
- Verify the GitHub Actions publish workflow has correct secrets mapping and Mill commands
- Remove any leftover Scala CLI build artifacts (`.scala-build/` directory contents)
- Final `mill __.compile` and `mill __.test` run to confirm everything is green

### Out of Scope
- Actual publishing to Maven Central (that happens after merge and tagging)
- Functional code changes to library sources
- Version bumps beyond the already-set `0.1.0`
- Adding new tests (existing 397 tests are the verification gate)

## Dependencies

### Prior Phases
- **Phase 1 (Build Infrastructure)**: `build.mill` with three modules. Completed.
- **Phase 2 (Source Reorganization)**: All sources in Mill layout, tests passing. Completed.
- **Phase 3 (Publishing Configuration)**: `artifactName` overrides, `publishVersion` 0.1.0, GitHub Actions workflow, `publishLocal` verified. Completed.

### External Dependencies
- Mill 1.1.2 installed and functional
- `mill-iw-support` 0.1.4-SNAPSHOT accessible

## Approach

### Step 1: Formal dependency tree inspection

Run `mill show core.ivyDepsTree`, `mill show direct.ivyDepsTree`, `mill show effectful.ivyDepsTree` and verify:

- **core**: Only circe-core and circe-parser (plus their transitive deps like cats-core). No ox, no os-lib, no cats-effect, no fs2, no log4cats.
- **direct**: core + os-lib + ox + slf4j-api. No cats-effect, no fs2, no log4cats.
- **effectful**: core + os-lib + cats-effect + fs2-core + fs2-io + log4cats-slf4j. No ox.

Record the output in the implementation log for audit purposes.

### Step 2: Update ARCHITECTURE.md

The current ARCHITECTURE.md describes the SDK architecture but does not mention:
- The Mill build tool or multi-module structure
- The three published artifacts and their Maven coordinates
- The module dependency graph (`direct -> core`, `effectful -> core`)
- How to build and test with Mill

Add a "Project Structure" or "Build Modules" section covering:
- Module layout: `core/src/`, `direct/src/`, `effectful/src/`
- Dependency graph between modules
- Published artifact coordinates: `works.iterative:claude-code-query-{core,direct,effectful}_3:0.1.0`
- Build commands: `mill __.compile`, `mill __.test`, `mill __.publishLocal`

### Step 3: Update README.md

The current README.md references Scala CLI for adding dependencies:
```scala
//> using dep "works.iterative::claude-scala-sdk:0.1.0"
```

Replace with proper Maven Central coordinates for each module:
```scala
// Mill
ivy"works.iterative::claude-code-query-direct:0.1.0"
ivy"works.iterative::claude-code-query-effectful:0.1.0"

// SBT
"works.iterative" %% "claude-code-query-direct" % "0.1.0"
"works.iterative" %% "claude-code-query-effectful" % "0.1.0"
```

Also update the "Requirements" section to mention Mill as the build tool and remove any Scala CLI references. Update the "Architecture" section to mention three separate modules.

### Step 4: Verify GitHub Actions workflow

Review `.github/workflows/publish.yml` for:
- Correct secret names (`SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `MILL_PGP_SECRET_BASE64`, `MILL_PGP_PASSPHRASE`)
- Mill bootstrap via `./mill` wrapper script
- Cache paths include Mill directories
- Test gate runs before publish
- Tag trigger pattern matches expected `v0.1.0` format

Note: The workflow currently uses `SONATYPE_USERNAME`/`SONATYPE_PASSWORD` as env var names but Mill expects `MILL_SONATYPE_USERNAME`/`MILL_SONATYPE_PASSWORD` by default. Verify which names the `SonatypeCentralPublishModule` actually reads and ensure the mapping is correct.

### Step 5: Cleanup leftover Scala CLI artifacts

The `.scala-build/` directory still exists with cached build artifacts from the Scala CLI era. While it is gitignored, verify there are no other Scala CLI remnants:
- No `project.scala` or `publish-conf.scala` (already confirmed deleted in Phase 2)
- No `.bsp/` entries pointing to Scala CLI (check if `.bsp/` should be regenerated for Mill)
- Gitignore includes appropriate Mill entries (`out/`)

### Step 6: Final compilation and test run

Run `mill __.compile` and `mill __.test` one final time to confirm:
- All three modules compile cleanly
- All 397 tests pass
- No new warnings introduced

## Files to Create/Modify

### Files to Modify
| File | Change |
|------|--------|
| `ARCHITECTURE.md` | Add "Build Modules" section with module layout, dependency graph, artifact coordinates, and build commands |
| `README.md` | Replace Scala CLI dependency with Maven Central coordinates for three modules; update Requirements section |
| `.github/workflows/publish.yml` | Fix Sonatype env var names if they don't match Mill's expected names |

### Files to Potentially Remove
| File/Dir | Reason |
|----------|--------|
| `.scala-build/` | Leftover Scala CLI cache (gitignored, but can be cleaned locally) |

## Testing Strategy

### Verification Steps

1. **Dependency tree inspection**: `mill show {core,direct,effectful}.ivyDepsTree` confirms no cross-contamination
2. **Compilation**: `mill __.compile` succeeds for all modules
3. **Tests**: `mill __.test` — all 397 tests pass
4. **Documentation review**: ARCHITECTURE.md and README.md accurately reflect current project state
5. **Workflow validation**: GitHub Actions YAML is syntactically valid and references correct secret names

### Smoke Checks

```bash
# Dependency tree verification
mill show core.ivyDepsTree 2>&1 | grep -i "cats-effect\|ox\|fs2"  # should return nothing
mill show direct.ivyDepsTree 2>&1 | grep -i "cats-effect\|fs2\|log4cats"  # should return nothing
mill show effectful.ivyDepsTree 2>&1 | grep -i "ox"  # should return nothing (os-lib is expected)

# Full test suite
mill __.test

# Workflow syntax check
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/publish.yml'))"
```

## Acceptance Criteria

- [ ] `mill show core.ivyDepsTree` contains only circe and its transitive deps (no ox, cats-effect, fs2)
- [ ] `mill show direct.ivyDepsTree` contains core + os-lib + ox + slf4j (no cats-effect, fs2, log4cats)
- [ ] `mill show effectful.ivyDepsTree` contains core + os-lib + cats-effect + fs2 + log4cats (no ox)
- [ ] `ARCHITECTURE.md` documents the three-module structure, dependency graph, artifact coordinates, and build commands
- [ ] `README.md` has Maven Central coordinates for `claude-code-query-direct` and `claude-code-query-effectful` (replacing Scala CLI dep)
- [ ] `README.md` Requirements section references Mill (not Scala CLI) as the build tool
- [ ] GitHub Actions workflow env var names match what Mill's `SonatypeCentralPublishModule` expects
- [ ] No Scala CLI config files remain in the repository (`project.scala`, `publish-conf.scala`)
- [ ] `mill __.compile` succeeds
- [ ] `mill __.test` passes all tests
- [ ] No leftover Scala CLI references in tracked files (excluding git history)
