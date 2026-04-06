# Phase 2 Tasks: CI pipeline updates

## Setup

- [x] [setup] Read current `.github/workflows/publish.yml`
- [x] [setup] Verify Phase 1 itest modules are in build.mill

## Implementation

- [x] [impl] Add `./mill __.itest` step to publish workflow after unit tests and before publish
- [x] [impl] Commit the workflow change

## Verification

- [x] [verify] Validate YAML syntax
- [x] [verify] Confirm step ordering: checkout → setup-java → cache → test → itest → publish
**Phase Status:** Complete
