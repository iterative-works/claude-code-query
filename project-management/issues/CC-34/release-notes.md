# Poznámky k vydání: Vyhledávání adresáře projektů Claude přímo v knihovně

**Issue:** CC-34
**Datum:** 2026-04-08

Tato aktualizace zjednodušuje práci s historií konverzací Claude Code pro všechny nástroje postavené nad touto knihovnou. Doposud si musel každý nástroj, který chtěl zobrazit sezení spojená s aktuálním pracovním adresářem, sám zjistit, kde Claude Code ukládá data na disku a jak se název pracovního adresáře převádí na název podsložky. Nově tuto znalost nese knihovna sama, takže se logika nemusí znovu a znovu opisovat v jednotlivých aplikacích.

Hlavním přínosem pro uživatele je automatické respektování nastavení proměnné prostředí `CLAUDE_CONFIG_DIR`. Pokud má uživatel Claude Code nakonfigurovaný tak, aby svá data ukládal mimo výchozí umístění v domovském adresáři – například do vlastní složky pro oddělení více pracovních prostředí – nástroje využívající tuto knihovnu toto nastavení nově převezmou samy a sezení najdou tam, kde je Claude Code skutečně uložil. Odpadá tak třída matoucích situací, kdy nástroj hlásil, že žádná historie neexistuje, přestože uživatel s Claude Code aktivně pracoval.

Druhým viditelným zlepšením je možnost dotazovat se na historii přímo podle pracovního adresáře. Nástroje mohou jednoduše říct „ukaž mi sezení pro tento adresář“ nebo „najdi konkrétní sezení v tomto adresáři“, aniž by musely samy rozumět tomu, jak Claude Code zakóduje cestu do názvu složky. Pro koncové uživatele to znamená spolehlivější propojení mezi místem, kde právě pracují, a historií konverzací, která se k tomuto místu vztahuje.

Stávající způsoby práce s historií zůstávají beze změny zachované, takže existující integrace fungují dál bez jakýchkoliv úprav. Jde o čistě rozšiřující změnu – přidává nové možnosti, aniž by cokoliv odebírala.
