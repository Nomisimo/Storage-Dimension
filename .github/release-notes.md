Storage Dimension für **Minecraft 26.1.2** (Fabric).

**Neu in 0.4.4:** Roboter-Station mit eigenem Fenster (echte Knöpfe und Textfelder): Stationsname, Filter (nur diese / alles außer diesen), Reichweite ab der Station, Farbe, Robotername (doppelte Namen werden durchnummeriert, kostet keine Level), Liste aller Roboter der Station (bis 32) mit Zustand und Entfernung, und ein Zurückrufen-Knopf (alle laufen sofort los und sammeln sich im 3×3-Feld um die Station, ab 21 Robotern 5×5; teleportiert wird nur, wer keinen Weg findet; danach eine Minute Pause an der Station). Roboter lassen sich nur noch an einer Station eingestellt hinstellen und folgen deren Einstellungen sofort. Sie arbeiten im Schwarm, warten fröhlich an der Station (jeder im eigenen Takt) und zeigen ein rotes 🚫 links und rechts vom Namen, wenn sie nicht arbeiten können (Fehler); wer 3 Minuten nicht zur Station durchkommt, teleportiert sich hin. Sie hüpfen bis zu zwei Blöcke hoch, auch über Zäune und Mauern, steigen aus Booten aus, tauchen mit Glashelm, schwimmen schnell mit Spritzspur und spannen bei Regen einen Schirm in ihrer Farbe auf. Mit Jade zeigt der Blick auf Roboter und Station deren Einstellungen und Zustand, mit Xaero's Minimap erscheinen die Roboter mit Gesicht auf der Karte. ME-Terminal: echte Knöpfe zum Blättern und Einlagern, Suche mit JEI koppelbar (Schalter „J“). Traveler's-Backpack-Add-on 0.4.4: Das ME-Upgrade zeigt den ME-Speicher direkt im Rucksack-Fenster an Stelle des Rucksack-Inhalts, aufgebaut wie die ME-Kiste (Suchleiste mit J-Schalter und Sortierung oben, Blättern, Einlagern und Seiten-/Mengenanzeige unten); Crafting und die anderen Upgrades bleiben daneben. JEIs „+“ ist nur noch grün, wenn wirklich alle Zutaten in ME, Rucksack oder Inventar liegen. Getestet mit JEI 29.47.0.111, Jade 26.1.11 und Xaero's Minimap 26.6.2.

**Neu in 0.4.3:** Roboter-Barriere (Plasma-Schild, nur Roboter kommen nicht durch). Sammelroboter planen den Weg vorher, überspringen unerreichbare Items sofort und bemerken neue Items schneller. ME-Mengen werden ab 1.000 abgekürzt (1k, 1.2k …). Traveler's-Backpack-Add-on 0.1.1: Es gibt nur noch ein ME-Upgrade, das auch mit dem Papier-Rezept entsteht, in den Upgrade-Slot passt und mit einem Bündel einen ME-Rucksack ergibt. Die Add-on-Rezepte stehen jetzt in JEI.

**Neu in 0.4.2:** Das ME-Terminal zeigt die echte Menge in kleiner Schrift auf jedem Symbol, ohne Grenze bei 99. Sammelroboter: Reichweite per Rechtsklick einstellbar (1 bis 10 Chunks), Item-Filter per Schleichen + Rechtsklick mit Item, und er bleibt nicht mehr an unerreichbaren Items oder hoch gestapelten Kisten hängen.

**Neu in 0.4.1:** Im ME-Speicher (auch über das Rucksack-Upgrade) und im Filter-Fenster lassen sich Items im eigenen Inventar wieder ziehen und verteilen sowie per Doppelklick sammeln; vorher sprangen sie zurück in die Hand.

**Neu in 0.4.0:** Sammelroboter, eigene Texturen für Dimensionskiste, Filterkiste, ME-Terminal und die ME-Items, Rezepte in JEI, Anbindung an Maschinen (z. B. Tech Reborn), doppelte Filterkisten mit 14 Filtern. Ab dieser Version brauchen **alle Spieler** `storage-dimension-0.4.4.jar`.

## Server, in den Ordner `mods`

- `storage-dimension-0.4.4.jar`
- `storagedim-tb-0.4.4.jar`
- dazu [Fabric API](https://modrinth.com/mod/fabric-api), [Traveler's Backpack](https://modrinth.com/mod/travelersbackpack) und [Forge Config API Port](https://modrinth.com/mod/forge-config-api-port), alle für 26.1.2

## Jeder Spieler, in den Ordner `mods`

- `storage-dimension-0.4.4.jar`
- `storagedim-tb-0.4.4.jar`
- dazu [Fabric API](https://modrinth.com/mod/fabric-api), [Traveler's Backpack](https://modrinth.com/mod/travelersbackpack), [Forge Config API Port](https://modrinth.com/mod/forge-config-api-port) und [JEI](https://modrinth.com/mod/jei), alle für 26.1.2

## Nur Storage Dimension, ohne Traveler's Backpack

`storage-dimension.mrpack` ist ein Modrinth-Pack (Fabric Loader 0.19.5, Fabric API und Storage Dimension). Es passt für den Server und, in der Modrinth-App, auch für die Spieler.
