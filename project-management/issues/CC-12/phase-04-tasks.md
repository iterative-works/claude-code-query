# Phase 04 Tasks: Verification

## Verification
- [x] [verify] Inspect dependency tree for `core` module: `mill show core.showMvnDepsTree` — confirm only circe and transitive deps (no ox, cats-effect, fs2, os-lib)
- [x] [verify] Inspect dependency tree for `direct` module: `mill show direct.showMvnDepsTree` — confirm core + os-lib + ox + slf4j-api (no cats-effect, fs2, log4cats)
- [x] [verify] Inspect dependency tree for `effectful` module: `mill show effectful.showMvnDepsTree` — confirm core + os-lib + cats-effect + fs2 + log4cats (no ox)
- [x] [verify] Run cross-contamination smoke checks: `mill show core.showMvnDepsTree 2>&1 | grep -i "cats-effect\|ox\|fs2"` (expect empty), `mill show direct.showMvnDepsTree 2>&1 | grep -i "cats-effect\|fs2\|log4cats"` (expect empty), `mill show effectful.showMvnDepsTree 2>&1 | grep -i "ox"` (expect empty)
- [x] [verify] Run `mill __.compile` and confirm all three modules compile cleanly with no warnings
- [x] [verify] Run `mill __.test` and confirm all 397 tests pass

## Workflow
- [x] [workflow] Review `.github/workflows/publish.yml` for correct secret names — verify whether `SonatypeCentralPublishModule` expects `MILL_SONATYPE_USERNAME`/`MILL_SONATYPE_PASSWORD` or `SONATYPE_USERNAME`/`SONATYPE_PASSWORD`, and fix if mismatched
- [x] [workflow] Verify publish workflow has Mill bootstrap via `./mill` wrapper, correct cache paths for Mill directories, test gate before publish, and tag trigger pattern matching `v*`
- [x] [workflow] Validate workflow YAML syntax: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/publish.yml'))"`

## Documentation
- [x] [docs] Update `ARCHITECTURE.md`: add "Build Modules" section covering module layout (`core/src/`, `direct/src/`, `effectful/src/`), dependency graph (`direct -> core`, `effectful -> core`), artifact coordinates (`works.iterative:claude-code-query-{core,direct,effectful}_3:0.1.0`), and build commands (`mill __.compile`, `mill __.test`, `mill __.publishLocal`)
- [x] [docs] Update `README.md`: replace Scala CLI dependency line with Maven Central coordinates for Mill (`ivy"works.iterative::claude-code-query-direct:0.1.0"`) and SBT (`"works.iterative" %% "claude-code-query-direct" % "0.1.0"`), covering both `direct` and `effectful` modules
- [x] [docs] Update `README.md` Requirements section: replace Scala CLI with Mill as the build tool, update build/test/run commands to Mill equivalents

## Cleanup
- [x] [cleanup] Remove `.scala-build/` directory (leftover Scala CLI cache)
- [x] [cleanup] Verify no Scala CLI config files remain in tracked files (`project.scala`, `publish-conf.scala`) — `git ls-files | grep -E 'project\.scala|publish-conf\.scala'` should return empty
- [x] [cleanup] Check `.bsp/` directory for stale Scala CLI entries; regenerate for Mill if needed (`mill mill.bsp.BSP/install`)
- [x] [cleanup] Verify `.gitignore` includes Mill's `out/` directory
- [x] [cleanup] Scan tracked files for leftover Scala CLI references: `git grep -l 'scala-cli\|//> using' -- ':!project-management/'` should return empty
