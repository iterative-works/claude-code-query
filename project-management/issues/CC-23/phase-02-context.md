# Phase 2: CI pipeline updates

## Goals

Add `./mill __.itest` step to the GitHub Actions CI workflow so that integration/E2E tests run as part of the release pipeline. After this phase, the publish workflow runs both unit tests and integration tests before publishing.

## Scope

### In Scope

- Add `./mill __.itest` step to `.github/workflows/publish.yml` (after unit tests, before publish)
- Ensure itest failures block the publish step

### Out of Scope

- Build module changes (completed in Phase 1)
- Adding separate CI workflow for PRs (not in scope for CC-23)
- Any changes to test files or test logic

## Dependencies

- Phase 1 must be complete (itest modules must exist in `build.mill`)
- The `itest` modules are already functional from Phase 1

## Approach

### 1. Add Integration Test Step

Add a new step in `.github/workflows/publish.yml` between "Run tests" and "Publish to Sonatype Central":

```yaml
- name: Run integration tests
  run: ./mill __.itest
```

Since GitHub Actions steps are sequential by default, a failure in the itest step will prevent the publish step from running.

### 2. Verification

- Validate the YAML syntax
- Confirm the step ordering is correct (unit tests → integration tests → publish)

## Files to Modify

- `.github/workflows/publish.yml` — add itest step

## Testing Strategy

### Verification Steps

1. YAML syntax validation (yamllint or manual review)
2. Step ordering is logical: checkout → setup → tests → itests → publish
3. No other workflow files need changes

## Acceptance Criteria

- [ ] `.github/workflows/publish.yml` includes `./mill __.itest` step
- [ ] Integration test step runs after unit tests and before publish
- [ ] YAML is syntactically valid
