# Poznámky k vydání: CC-23

## Souhrn změn

Rozdělení testů do samostatných Mill modulů — rychlé unit testy (`test`) a integrační/E2E testy (`itest`).

## Co se změnilo

- Nový příkaz `./mill __.itest` spouští integrační a E2E testy (vyžadují Claude CLI)
- Příkaz `./mill __.test` nyní spouští pouze unit testy (čistá logika, in-memory mocky)
- Moduly `core`, `direct` a `effectful` mají nové `itest` submoduly v `build.mill`
- CI pipeline (`.github/workflows/publish.yml`) nově spouští i `./mill __.itest`
- Aktualizována dokumentace (`CLAUDE.md`, `ARCHITECTURE.md`, `README.md`)

## Dopad na uživatele

- **Publikované artefakty se nemění** — žádný dopad na runtime ani na API
- Vývojáři pracující s SDK mohou nyní spouštět rychlé unit testy bez nutnosti mít nainstalované Claude CLI
- Integrační testy vyžadující Claude CLI jsou izolované v `itest` modulech
