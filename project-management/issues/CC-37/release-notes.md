# Podpora pro objevování sub-agentů

**Issue:** CC-37
**Datum:** 2026-04-09

Knihovna `claude-code-query` nově umožňuje pracovat s celým stromem konverzace, včetně sub-agentů, které Claude Code vytváří při složitějších úlohách. Dosud bylo možné číst pouze hlavní sezení — dílčí agenty (například implementátory, code reviewery nebo průzkumné agenty) knihovna přehlížela, což znamenalo, že nástroje postavené nad touto knihovnou viděly jen část skutečné práce.

Od této verze knihovna dokáže pro každé sezení vyhledat všechny sub-agenty a přečíst jejich metadata — tedy typ agenta a jeho popis. Díky tomu je možné rekonstruovat kompletní časovou osu vývoje, od zadání úkolu přes delegování na specializované agenty až po výsledek. Tato funkce je dostupná jak v synchronním, tak v asynchronním rozhraní knihovny.

Změna je zpětně kompatibilní a nemění chování stávajících funkcí. Aplikace využívající předchozí verzi knihovny budou po aktualizaci fungovat beze změn, nové možnosti jsou k dispozici okamžitě.
