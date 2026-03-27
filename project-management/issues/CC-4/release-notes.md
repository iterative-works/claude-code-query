# Podpora čtení konverzačních logů Claude Code

**Issue:** CC-4
**Datum:** 2026-03-25

SDK nyní umožňuje číst a analyzovat kompletní konverzační logy, které Claude Code ukládá ve formátu JSONL v adresáři `~/.claude/projects/`. Díky tomu mohou externí nástroje přistupovat k datům, která nejsou dostupná přes streamovací rozhraní — zejména k obsahu rozšířeného myšlení (thinking blocks), detailním informacím o spotřebě tokenů a kompletní struktuře konverzací včetně větvení.

Nové rozhraní poskytuje dva hlavní způsoby práce s logy. Služba pro indexování logů umožňuje procházet dostupné konverzační soubory, filtrovat je podle projektu a vyhledávat konkrétní relace podle jejich identifikátoru. Služba pro čtení logů pak zpracovává jednotlivé soubory a vrací proud typovaných záznamů — uživatelské zprávy, odpovědi asistenta s informacemi o použitém modelu a spotřebě, systémové události, záznamy o průběhu a další typy.

Obě služby jsou k dispozici ve dvou variantách podle stávajícího vzoru SDK: synchronní varianta využívající Ox a přímé I/O operace, a efektová varianta postavená na cats-effect a fs2 pro funkcionální reaktivní zpracování. Uživatelé si tak mohou zvolit přístup, který odpovídá architektuře jejich aplikace.
