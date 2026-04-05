# CC-15: Persistentni konverzacni session pro Claude Code CLI

**Datum:** 2026-04-05

SDK nyni podporuje viceturnove konverzacni session s procesem Claude Code CLI. Namisto jednorázovych dotazu, kde se pro kazdy pozadavek spoustel novy proces, je nyni mozne otevrit dlouhodobou session a vest s CLI souvisly dialog — vcetne navazovani na predchozi kontext konverzace.

## Nova funkcionalita

Session komunikuji s CLI prostrednictvim protokolu `--input-format stream-json`. SDK spravuje zivotni cyklus procesu, odesila uživatelske zpravy ve formatu `SDKUserMessage` na stdin a cte proud odpovedi z stdout. Kazdy turn konverzace je ukoncen prijmem `ResultMessage`, coz umoznuje strídání dotazu a odpovedi v ramci jedne session.

Konfigurace session se provadi prostrednictvim `SessionOptions` — datove tridy s 18 parametry pokryvajicimi pracovni adresar, model, system prompt, povolene nastroje, MCP servery a dalsi. Vsechny parametry jsou volitelne s rozumnými výchozimi hodnotami.

Obe stavajici API varianty nyni nabizeji session:

- **Direct API (Ox):** `ClaudeCode.session(options)(Ox)` vraci `Session` s metodami `send(prompt)` a `stream()`. Session se zavre automaticky pri ukonceni Ox scope.
- **Effectful API (cats-effect/fs2):** `ClaudeCode.session(options)` vraci `Resource[IO, Session]`. Zpravy se ctou jako `Stream[IO, Message]` a session se uklizi pri uvolneni resource.

V obou pripadech metoda `send` odesle textovy prompt jako JSON zpravu a `stream` vraci proud typovanych zprav az do vysledku aktuálniho turnu.

## Osetreni chyb

Session spravne reaguje na nestandardni situace:

- **Pad procesu** — pokud CLI proces neocekavane skonci, SDK emituje `SessionProcessDied` s navratovym kodem a stderr obsahem.
- **Uzavrena session** — pokus o odeslani zpravy do jiz ukoncene session vyvolá `SessionClosedError`.
- **Poskozeny JSON** — vadne radky na stdout jsou preskoceny s varovanim misto ukonceni cele session.

## Nove typy

- `Session` — rozhrani pro komunikaci s CLI procesem (v modulech `direct` i `effectful`)
- `SessionOptions` — konfigurace session (modul `core`)
- `SDKUserMessage` — format zpravy odeslane na stdin procesu (modul `core`)
- `CLIError.SessionProcessDied` — chyba pri neocekavanem ukonceni procesu
- `CLIError.SessionClosedError` — chyba pri pokusu o komunikaci s uzavrenou session

## Zpetna kompatibilita

Zadne stavajici API se nemeni. Jednorázove metody `query` a `stream` na `ClaudeCode` funguji beze zmen. Session jsou ciste aditivni rozsireni.
