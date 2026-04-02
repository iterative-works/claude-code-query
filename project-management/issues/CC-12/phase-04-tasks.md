# Phase 04 Tasks: Verification

## Verification
- [ ] [verify] Inspect dependency tree for `core` module: `mill show core.ivyDepsTree` — confirm only circe and transitive deps (no ox, cats-effect, fs2, os-lib)
- [ ] [verify] Inspect dependency tree for `direct` module: `mill show direct.ivyDepsTree` — confirm core + os-lib + ox + slf4j-api (no cats-effect, fs2, log4cats)
- [ ] [verify] Inspect dependency tree for `effectful` module: `mill show effectful.ivyDepsTree` — confirm core + os-lib + cats-effect + fs2 + log4cats (no ox)
- [ ] [verify] Run cross-contamination smoke checks: `mill show core.ivyDepsTree 2>&1 | grep -i "cats-effect\|ox\|fs2"` (expect empty), `mill show direct.ivyDepsTree 2>&1 | grep -i "cats-effect\|fs2\|log4cats"` (expect empty), `mill show effectful.ivyDepsTree 2>&1 | grep -i "ox"` (expect empty)
- [ ] [verify] Run `mill __.compile` and confirm all three modules compile cleanly with no warnings
- [ ] [verify] Run `mill __.test` and confirm all 397 tests pass

## Workflow
- [ ] [workflow] Review `.github/workflows/publish.yml` for correct secret names — verify whether `SonatypeCentralPublishModule` expects `MILL_SONATYPE_USERNAME`/`MILL_SONATYPE_PASSWORD` or `SONATYPE_USERNAME`/`SONATYPE_PASSWORD`, and fix if mismatched
- [ ] [workflow] Verify publish workflow has Mill bootstrap via `./mill` wrapper, correct cache paths for Mill directories, test gate before publish, and tag trigger pattern matching `v*`
- [ ] [workflow] Validate workflow YAML syntax: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/publish.yml'))"`

## Documentation
- [ ] [docs] Update `ARCHITECTURE.md`: add "Build Modules" section covering module layout (`core/src/`, `direct/src/`, `effectful/src/`), dependency graph (`direct -> core`, `effectful -> core`), artifact coordinates (`works.iterative:claude-code-query-{core,direct,effectful}_3:0.1.0`), and build commands (`mill __.compile`, `mill __.test`, `mill __.publishLocal`)
- [ ] [docs] Update `README.md`: replace Scala CLI dependency line with Maven Central coordinates for Mill (`ivy"works.iterative::claude-code-query-direct:0.1.0"`) and SBT (`"works.iterative" %% "claude-code-query-direct" % "0.1.0"`), covering both `direct` and `effectful` modules
- [ ] [docs] Update `README.md` Requirements section: replace Scala CLI with Mill as the build tool, update build/test/run commands to Mill equivalents

## Cleanup
- [ ] [cleanup] Remove `.scala-build/` directory (leftover Scala CLI cache)
- [ ] [cleanup] Verify no Scala CLI config files remain in tracked files (`project.scala`, `publish-conf.scala`) — `git ls-files | grep -E 'project\.scala|publish-conf\.scala'` should return empty
- [ ] [cleanup] Check `.bsp/` directory for stale Scala CLI entries; regenerate for Mill if needed (`mill mill.bsp.BSP/install`)
- [ ] [cleanup] Verify `.gitignore` includes Mill's `out/` directory
- [ ] [cleanup] Scan tracked files for leftover Scala CLI references: `git grep -l 'scala-cli\|//> using' -- ':!project-management/'` should return empty
