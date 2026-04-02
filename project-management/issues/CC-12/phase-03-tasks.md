# Phase 03 Tasks: Publishing Configuration

## Tests
- [ ] [test] Write a verification script that runs `publishLocal` for all 3 modules and checks the generated POM files contain correct groupId, artifactId (`claude-code-query-core`, `claude-code-query-direct`, `claude-code-query-effectful`), version, and required Sonatype Central metadata (name, description, url, licenses, scm, developers)

## Implementation
- [ ] [impl] Add `artifactName` override in `SharedModule` to prefix `claude-code-query-` to each module name
- [ ] [impl] Bump `publishVersion` from `0.1.0-SNAPSHOT` to `0.1.0`
- [ ] [impl] Create GitHub Actions publish workflow (`.github/workflows/publish.yml`) triggered on release tags, running `mill __.publishSonatypeCentral` with `SONATYPE_USERNAME` and `SONATYPE_PASSWORD` env vars from secrets

## Integration
- [ ] [integration] Run `mill __.publishLocal` and verify all 3 artifacts appear under `~/.ivy2/local` with correct coordinates
- [ ] [integration] Inspect each generated POM for completeness (licenses, scm, developers, dependencies) and correct artifact naming
- [ ] [integration] Run full test suite (`mill __.test`) to confirm no regressions from build changes
