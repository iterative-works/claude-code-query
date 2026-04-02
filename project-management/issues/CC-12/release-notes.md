# CC-12: Rozdělení knihovny do samostatných artefaktů a publikace na Maven Central

**Datum:** 2026-04-02
**Verze:** 0.1.0

Knihovna claude-code-query je nově dostupná na Maven Central jako tři samostatné artefakty. Uživatelé si nyní mohou vybrat pouze tu variantu, kterou skutečně potřebují, a vyhnout se tak zbytečným závislostem ve svém projektu.

Dosud bylo nutné stáhnout celou knihovnu včetně všech závislostí — jak na Ox, tak na cats-effect — bez ohledu na to, kterou variantu API uživatel ve skutečnosti používal. Nově je knihovna rozdělena do tří částí: sdílené jádro obsahující datové typy a parsování, modul pro přímý styl programování postavený na Ox a modul pro efektový styl postavený na cats-effect a fs2. Každý modul přináší pouze své vlastní závislosti, takže projekty používající Ox nemusí stahovat cats-effect a naopak.

Artefakty jsou publikovány na Maven Central pod skupinou `works.iterative` a lze je přidat do projektu následovně:

**Mill (direct / Ox):**
```scala
ivy"works.iterative::claude-code-query-direct:0.1.0"
```

**Mill (effectful / cats-effect):**
```scala
ivy"works.iterative::claude-code-query-effectful:0.1.0"
```

**SBT (direct / Ox):**
```scala
"works.iterative" %% "claude-code-query-direct" % "0.1.0"
```

**SBT (effectful / cats-effect):**
```scala
"works.iterative" %% "claude-code-query-effectful" % "0.1.0"
```

Modul `core` se přidávat nemusí — je automaticky zahrnut jako tranzitivní závislost obou hlavních modulů.

Při přechodu na novou verzi je třeba upravit importy. Dřívější import `works.iterative.claude.ClaudeCode` je nahrazen importem `works.iterative.claude.effectful.ClaudeCode` (pro efektový styl) nebo `works.iterative.claude.direct.ClaudeCode` (pro přímý styl). Datové typy z balíku `works.iterative.claude.model` se nyní nacházejí v `works.iterative.claude.core.model`. Funkčně se API knihovny nemění — jedná se čistě o změnu balíčkování a distribuce.
