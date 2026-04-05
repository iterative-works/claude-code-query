# Phase 2 Tasks: CI pipeline updates

## Setup

- [ ] [setup] Read current `.github/workflows/publish.yml`
- [ ] [setup] Verify Phase 1 itest modules are in build.mill

## Implementation

- [ ] [impl] Add `./mill __.itest` step to publish workflow after unit tests and before publish
- [ ] [impl] Commit the workflow change

## Verification

- [ ] [verify] Validate YAML syntax
- [ ] [verify] Confirm step ordering: checkout → setup-java → cache → test → itest → publish
