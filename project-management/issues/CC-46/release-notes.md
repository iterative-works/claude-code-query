# Izolace nástrojů pro spouštění Claude Code v uzavřeném režimu

**Issue:** CC-46
**Datum:** 2026-04-21

Tato změna rozšiřuje Scala SDK pro Claude Code o čtyři nové volby, které umožňují spustit Claude Code v plně izolovaném režimu s pevně daným seznamem povolených nástrojů. Volající nyní může zabránit tomu, aby podprocesy spuštěné přes SDK automaticky přebíraly konfiguraci nástrojů z uživatelského nastavení, z nalezených `.mcp.json` souborů v pracovním adresáři, nebo aby při pokusu o použití nepovoleného nástroje čekaly na interaktivní potvrzení.

V praxi to znamená, že aplikace, které potřebují spouštět Claude Code jako součást automatizovaných toků (například zpracování událostí nebo dávkové operace), mohou nyní vynutit, aby podproces používal pouze explicitně předaný seznam povolených nástrojů a žádnou jinou konfiguraci. Nepovolené volání nástroje už neskončí zavěšením na dotazu, ale okamžitým odmítnutím, což zásadně zvyšuje spolehlivost těchto běhů v prostředích bez uživatelské interakce.

Konkrétně lze nově zapnout striktní zpracování konfigurace nástrojů (ignorování jiných zdrojů), předat cestu ke konkrétnímu konfiguračnímu souboru, omezit zdroje, ze kterých se načítají obecná nastavení (například pouze na projekt), a zvolit režim oprávnění, který vyžaduje pevný allow-list bez výzev. Tyto čtyři volby lze kombinovat nezávisle nebo dohromady — podle míry izolace, kterou daný scénář potřebuje.

Pro stávající volající se nic nemění. Všechny nové volby mají výchozí chování, které odpovídá dosavadnímu stavu, a jsou aktivní pouze tehdy, když je volající explicitně nastaví. Aktualizace tedy nevyžaduje žádné úpravy existujícího kódu a představuje čistě rozšíření dostupných možností.
