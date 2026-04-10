# Oprava parsování konverzačních logů

**Issue:** CC-41
**Datum:** 2026-04-10

Parsování konverzačních logů z Claude Code nyní správně rozpoznává všechny typy záznamů, které se v reálných přepisech vyskytují. Dříve byly uživatelské zprávy, metadata subagentů a některé další záznamy tiše zahazovány, což vedlo k neúplným výsledkům analýzy — například počet uživatelských vstupů byl vždy nulový a informace o subagenttech zcela chyběly.

Po opravě systém korektně zpracovává uživatelské zprávy, záznamy o historii souborů, operacích ve frontě, oprávněních a přílohách. Metadata subagentů jsou nyní spolehlivě načítána, takže analýza přepisů zahrnuje kompletní přehled o rolích a aktivitách všech subagentů v konverzaci. Záznamy, které neobsahují identifikátor UUID, již nejsou ignorovány a jsou korektně zahrnuty do výsledků.

Díky těmto změnám poskytuje analýza přepisů úplný a přesný obraz o průběhu konverzací, včetně správného počtu interakcí, tokenových metrik a atribuce rolí subagentů.
