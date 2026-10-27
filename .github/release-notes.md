Storage Dimension für **Minecraft 26.1.2** (Fabric).

**Neu in 0.5.0:** Roboter-Station mit eigenem Fenster (echte Knöpfe und Textfelder): Stationsname, Filter (nur diese / alles außer diesen), Reichweite, Farbe und Robotername (doppelte Namen werden durchnummeriert, kostet keine Level), dazu eine Liste aller Roboter der Station (bis 32) mit Zustand und Entfernung. Roboter lassen sich nur noch an einer Station eingestellt hinstellen und folgen deren Einstellungen sofort. Roboter lagern nur noch in Speicher direkt an ihrer Station (Kisten, Fässer, Shulker- und Enderkisten, Dimensions-, Filter- und ME-Blöcke, Speicher anderer Mods; nie in Trichter oder Maschinen). Sind alle voll, warten sie traurig („Kisten voll“) und räumen weiter ein, sobald wieder Platz ist. Zurückrufen-Knopf: Alle laufen sofort los („auf dem Weg“), sammeln sich im 3×3-Feld um die Station (ab 21 Robotern 5×5) und warten eine Minute; dabei schauen sie den Spieler an, der gerufen hat. Teleportiert wird nur, wer keinen Weg findet; wer 3 Minuten nicht zur Station durchkommt, teleportiert sich ebenfalls hin. Kann ein Roboter nicht arbeiten, stehen rote 🚫 links und rechts von seinem Namen, der Grund steht als „Fehler: …“ in Jade und in der Stationsliste. Roboter hüpfen bis zu zwei Blöcke hoch, auch über Zäune und Mauern, steigen aus Booten aus, tauchen mit Glashelm, schwimmen schnell mit Spritzspur, spannen bei Regen einen Schirm in ihrer Farbe auf und wackeln beim Warten jeder im eigenen Takt. Mit Jade zeigt der Blick auf Roboter und Station Einstellungen und Zustand, mit Xaero's Minimap erscheinen die Roboter mit Gesicht auf der Karte. Filterkisten mit verzauberten Items als Filter nehmen nur Items mit genau diesen Verzauberungen. ME-Terminal: echte Knöpfe zum Blättern und Einlagern. Traveler's-Backpack-Add-on 0.5.0: Die ME-Ansicht im Rucksack ist aufgebaut wie die ME-Kiste (Suchleiste mit J-Schalter und Sortierung oben, Blättern, Einlagern und Mengenanzeige unten), die Rucksack-Knöpfe sind dort ausgeblendet. Getestet mit JEI 29.47.0.111, Jade 26.1.11 und Xaero's Minimap 26.6.2.

**Neu in 0.4.4:** Roboter-Station: stellt Reichweite (ab der Station), Filter, Farbe und Namen ein; Roboter kommt eingestellt aus dem Ausgang, die Einstellungen stehen im Tooltip. Namen schweben über den Robotern. Roboter arbeiten im Schwarm (jeder holt ein anderes Item), warten ohne Arbeit fröhlich an ihrer Station und sitzen traurig da, wenn sie die Station nicht erreichen oder keine Kiste in Reichweite ist. Sie stellen sich zum Aufheben direkt aufs Item. Das ME-Terminal kann seine Suche mit der JEI-Suchleiste koppeln (Schalter „J“). Traveler's-Backpack-Add-on 0.4.4 (hat jetzt dieselbe Versionsnummer): Das ME-Upgrade zeigt den ME-Speicher direkt im Rucksack-Fenster an Stelle des Rucksack-Inhalts, Crafting und die anderen Upgrades bleiben daneben; ein zweiter Klick zeigt wieder den Rucksack. JEIs „+“ ist nur noch grün, wenn wirklich alle Zutaten in ME, Rucksack oder Inventar liegen. Getestet mit JEI 29.47.0.111.

**Neu in 0.4.3:** Roboter-Barriere (Plasma-Schild, nur Roboter kommen nicht durch). Sammelroboter planen den Weg vorher, überspringen unerreichbare Items sofort und bemerken neue Items schneller. ME-Mengen werden ab 1.000 abgekürzt (1k, 1.2k …). Traveler's-Backpack-Add-on 0.1.1: Es gibt nur noch ein ME-Upgrade, das auch mit dem Papier-Rezept entsteht, in den Upgrade-Slot passt und mit einem Bündel einen ME-Rucksack ergibt. Die Add-on-Rezepte stehen jetzt in JEI.

**Neu in 0.4.2:** Das ME-Terminal zeigt die echte Menge in kleiner Schrift auf jedem Symbol, ohne Grenze bei 99. Sammelroboter: Reichweite per Rechtsklick einstellbar (1 bis 10 Chunks), Item-Filter per Schleichen + Rechtsklick mit Item, und er bleibt nicht mehr an unerreichbaren Items oder hoch gestapelten Kisten hängen.

**Neu in 0.4.1:** Im ME-Speicher (auch über das Rucksack-Upgrade) und im Filter-Fenster lassen sich Items im eigenen Inventar wieder ziehen und verteilen sowie per Doppelklick sammeln; vorher sprangen sie zurück in die Hand.

**Neu in 0.4.0:** Sammelroboter, eigene Texturen für Dimensionskiste, Filterkiste, ME-Terminal und die ME-Items, Rezepte in JEI, Anbindung an Maschinen (z. B. Tech Reborn), doppelte Filterkisten mit 14 Filtern. Ab dieser Version brauchen **alle Spieler** `storage-dimension-0.5.0.jar`.

## Server, in den Ordner `mods`

- `storage-dimension-0.5.0.jar`
- `storagedim-tb-0.5.0.jar`
- dazu [Fabric API](https://modrinth.com/mod/fabric-api), [Traveler's Backpack](https://modrinth.com/mod/travelersbackpack) und [Forge Config API Port](https://modrinth.com/mod/forge-config-api-port), alle für 26.1.2

## Jeder Spieler, in den Ordner `mods`

- `storage-dimension-0.5.0.jar`
- `storagedim-tb-0.5.0.jar`
- dazu [Fabric API](https://modrinth.com/mod/fabric-api), [Traveler's Backpack](https://modrinth.com/mod/travelersbackpack), [Forge Config API Port](https://modrinth.com/mod/forge-config-api-port) und [JEI](https://modrinth.com/mod/jei), alle für 26.1.2

## Nur Storage Dimension, ohne Traveler's Backpack

`storage-dimension.mrpack` ist ein Modrinth-Pack (Fabric Loader 0.19.5, Fabric API und Storage Dimension). Es passt für den Server und, in der Modrinth-App, auch für die Spieler.
