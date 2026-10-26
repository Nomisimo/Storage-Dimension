# Storage Dimension

Ein Fabric-Mod für **Minecraft 26.1.2**. Seit Version 0.4.0 (Sammelroboter, eigene Texturen) muss `storage-dimension-*.jar` **auf dem Server und bei allen Spielern** liegen.

**Download:** Die fertigen Dateien gibt es unter [Releases](https://github.com/Nomisimo/Storage-Dimension/releases). Dort steht auch, welche Datei wohin gehört.

![Dimensionskiste, Filterkiste, doppelte Filterkiste und ME-Terminal, davor der Sammelroboter](docs/screenshots/kisten-und-roboter.png)

## Was der Mod macht

- **Dimensionskiste:** Eine violette Kiste mit Endsternen. Wer sie öffnet und Zugriff auf den Speicher hat, wird in den Lagerraum in der Storage Dimension gezogen. Es können beliebig viele Spieler gleichzeitig drin sein. Wer keinen Zugriff hat, sieht nur ein leeres Kisten-Inventar, in dem nichts gelagert werden kann.
- **ME-Speicher:** Die Enderkiste im Raum ist das Terminal. Dort liegen alle Items ohne Stapel- oder Slot-Grenzen. Die genaue Menge steht im Namen jedes Items (z. B. „Bruchstein × 1.234“) und in kleiner Schrift auf dem Symbol, ohne Obergrenze (ab 100.000 abgekürzt, z. B. 123k oder 1.2M).
  - Linksklick nimmt einen Stapel, Rechtsklick einen halben, Shift-Klick schiebt ihn ins Inventar.
  - Item auf dem Cursor in das Terminal klicken oder Shift-Klick im eigenen Inventar lagert ein.
  - Der Trichter-Knopf lagert das ganze Inventar (ohne Hotbar) ein.
  - **Suche und Sortierung:** Oben links im Fenster sitzt ein Suchfeld, rechts daneben der Sortier-Knopf (*Menge* / *A–Z*). Gesucht wird beim Tippen.
  - Die Suche ist beim nächsten Öffnen wieder leer, die Sortierung bleibt.
- **Hopper-Eingang:** Trichter, die in eine Dimensionskiste oder Filterkiste zeigen, liefern direkt in den Speicher. Zum Anbauen mit einem Item in der Hand schleichen.
- **Maschinen anderer Mods (z. B. Tech Reborn, Advanced Reborn):** Dimensions- und Filterkisten sind über die Fabric-Transfer-API angebunden. Was eine Maschine hineinschiebt, landet direkt im ME-Speicher, und aus einer Filterkiste können Maschinen die Filter-Items herausziehen.
- **Sammelroboter:** Ein kleiner Roboter mit Schnabel. Mit dem Roboter-Item auf einen Block klicken stellt ihn hin, die Stelle ist sein Zuhause. Er sammelt im Umkreis von bis zu 160 Blöcken (10 Chunks) herumliegende Items ein, immer einen Stapel im Schnabel, und bringt ihn zur nächsten Dimensionskiste, zum ME-Terminal oder zu einer passenden Filterkiste. Er läuft Stufen hoch und runter. Ohne Arbeit setzt er sich hin.
  - Farbstoff auf den Roboter färbt Schnabel und Füße.
  - Rechtsklick mit leerer Hand stellt die Reichweite um: 1, 2, 4, 6, 8 oder 10 Chunks.
  - Schleichen + Rechtsklick mit einem Item setzt es auf seinen Filter oder nimmt es wieder herunter (bis zu 9 Items). Mit Filter sammelt er nur diese Items, ohne Filter alles.
  - Kommt er an ein Item oder eine Kiste nicht heran (eingemauert, in Lava, zu hoch), lässt er sie nach ein paar Sekunden für eine Minute in Ruhe und holt das nächste. Kisten erreicht er bis etwa drei Blöcke über dem Boden.
  - Schleichen + Rechtsklick mit leerer Hand nimmt ihn wieder auf, samt dem Item im Schnabel.
  - Nur Spieler können ihm schaden.
  - Er arbeitet nur in geladenen Chunks.

  ![Der Sammelroboter holt Items und bringt sie zu den Kisten](docs/screenshots/roboter-bei-der-arbeit.png)

- **Werkbank und Ofen** stehen im Raum, gecraftet wird direkt in der Dimension.
- **Gemeinsamer Speicher:** Standardmäßig teilen sich alle Spieler einen Speicher und einen Raum. Mit `"sharedStorage": false` in `config/storagedim.json` bekommt jeder seinen eigenen, der sich per `/storage teilen` freigeben lässt.
- **Filterkiste:** Eine Kiste, die nur bis zu 7 ausgewählte Items aus dem ME-Speicher zeigt, eins pro Rahmen im Rezept. Zwei Filterkisten nebeneinander werden eine Doppelkiste mit 14 Filter-Plätzen (eine normale Kiste verbindet sich nie mit einer Filterkiste). Wird eine Hälfte abgebaut, behält die andere die ersten 7 Filter. Schleichen + Rechtsklick mit leerer Hand öffnet die Filter: ein Item auf einen Platz legen setzt den Filter, Klick mit leerer Hand entfernt ihn. Normaler Rechtsklick öffnet die Kiste, dort kann man die gefilterten Items herausnehmen und nur passende einlagern. Trichter, die hineinzeigen, liefern auch hier direkt in den Speicher. Ein Trichter **unter** der Filterkiste zieht die Filter-Items aus dem ME-Speicher heraus (bis zu ein Stapel pro Filter liegt dafür bereit und geht zurück in den Speicher, wenn ihn keiner abholt).
- **ME-Terminal:** Eine uralte Kiste aus Tiefenschiefer mit Sculk-Rune. Hingestellt öffnet sie per Rechtsklick direkt den ME-Speicher, ohne Dimensionswechsel.
- **ME-Rucksack:** Ein Rucksack, der mit dem Speicher verbunden ist und ihn von überall öffnet.
  - **Bündel:** ME-Upgrade + Bündel in der Werkbank kombinieren. Rechtsklick öffnet den Speicher.
  - **[Traveler's Backpack](https://www.curseforge.com/minecraft/mc-mods/travelers-backpack-fabric) (optional):** ME-Upgrade in die Zweithand, den Rucksack in die Haupthand, schleichen und rechtsklicken. Danach öffnet Schleichen + Rechtsklick mit dem Rucksack den ME-Speicher, normaler Rechtsklick weiterhin den Rucksack. Trägst du ihn auf dem Rücken, öffnet `/storage me` den Speicher. Traveler's Backpack muss dafür auf Server und Clients installiert sein.
- **Ausgang:** Rechtsklick auf den Leuchtstein im Raum oder `/storage raus`.

![Dimensionskiste, Filterkiste, ME-Terminal und Sammelroboter im Inventar](docs/screenshots/items.png)

## Rezepte

Dimensionskiste (A = Amethystscherbe, P = Enderperle, C = Kiste):

![Rezept Dimensionskiste](docs/screenshots/rezept-storage-chest.png)

ME-Terminal (R = Redstone, A = Amethystscherbe, E = Enderkiste):

![Rezept ME-Terminal](docs/screenshots/rezept-me-terminal.png)

Filterkiste (8× Rahmen um eine Kiste, unten mittig ein Enderauge):

![Rezept Filterkiste](docs/screenshots/rezept-filter-chest.png)

Sammelroboter (Eisenbarren, Kürbis, Redstone, Trichter, Eisennuggets):

![Rezept Sammelroboter](docs/screenshots/rezept-robot.png)

ME-Upgrade (formlos: Enderauge, 2× Amethystscherbe, Redstoneblock, Papier):

![Rezept ME-Upgrade](docs/screenshots/rezept-me-upgrade.png)

ME-Rucksack (formlos: beliebiges Bündel + ME-Upgrade):

![Rezept ME-Rucksack](docs/screenshots/rezept-me-backpack.png)

Alle Rezepte stehen auch in [JEI](https://modrinth.com/mod/jei).

## Befehle

| Befehl | Wirkung |
| --- | --- |
| `/storage` | Infos zum eigenen Speicher |
| `/storage teilen <spieler>` | Lädt jemanden in deinen Speicher ein (nur ohne gemeinsamen Speicher) |
| `/storage annehmen` | Nimmt eine Einladung an. Dein eigener Speicher bleibt erhalten. |
| `/storage verlassen` | Zurück zum eigenen Speicher |
| `/storage entfernen <spieler>` | Wirft ein Mitglied raus |
| `/storage kiste besitzer <spieler>` | Danach eine eigene Dimensionskiste anklicken, um sie jemandem zu übertragen |
| `/storage raus` | Verlässt den Lagerraum |
| `/storage me` | Öffnet den ME-Speicher, wenn du einen ME-Rucksack trägst oder dabeihast |
| `/storage give [spieler]` | (OP) Gibt eine Dimensionskiste |
| `/storage give terminal` | (OP) Gibt ein ME-Terminal |
| `/storage give filter` | (OP) Gibt eine Filterkiste |
| `/storage give robot` | (OP) Gibt einen Sammelroboter |

## Bauen

Java 25 wird benötigt.

```
cd storage-dimension
./gradlew build
```

Die fertige Datei liegt in `storage-dimension/build/libs/`. GitHub Actions baut bei jedem Push automatisch und legt die Jars auf den Branch `builds`. Ein Release mit Anleitung entsteht per Hand unter *Actions → release → Run workflow*.

## Installation

1. Fabric Loader 0.19.5 oder neuer und Fabric API für 26.1.2 auf dem Server und bei allen Spielern installieren.
2. Die `storage-dimension-*.jar` (nicht die `-sources.jar`) auf dem Server und bei allen Spielern in den `mods`-Ordner legen.
3. Server starten. Die Dimension `storagedim:storage` wird automatisch angelegt.

Die Daten liegen in `world/storagedim.json`.

### Traveler's-Backpack-Add-on (optional)

Im Ordner `storagedim-tb/` liegt ein kleiner Zusatz-Mod. Er fügt ein echtes **ME-Upgrade** für den Upgrade-Slot von Traveler's Backpack hinzu.

- Rezept (formlos): Leeres Upgrade (Traveler's Backpack), Enderauge, 2× Amethystscherbe, Redstoneblock.
- Upgrade in einen Upgrade-Slot des Rucksacks legen. Linksklick auf das ME-Symbol im Rucksack-Fenster öffnet den ME-Speicher, Rechtsklick nimmt das Upgrade wieder heraus.
- Der Rucksack muss nicht in der Hand sein: auf dem Rücken tragen und mit der Rucksack-Taste von Traveler's Backpack (Standard **B**) öffnen reicht.
- **Crafting aus dem ME-Speicher:** Hat der Rucksack zusätzlich das Crafting-Upgrade, holt der „+“-Knopf von [JEI](https://modrinth.com/mod/jei) fehlende Zutaten auch aus dem ME-Speicher, genauso wie aus Inventar und Rucksack. Shift + „+“ füllt das Raster für so viele Durchgänge wie möglich (bis zu einem Stapel). Was vorher im Raster lag, kommt zurück ins Inventar oder, wenn das voll ist, in den Speicher.
  - Der kleine Schalter neben dem ME-Symbol stellt zwischen *ME als Quelle* (Standard) und *nur Inventar* um. Auf *nur Inventar* arbeitet „+“ wie ohne Upgrade.
- Installation: `storagedim-tb-*.jar` und `storage-dimension-*.jar` **auf dem Server und bei allen Spielern**, zusammen mit Traveler's Backpack (und dessen Abhängigkeiten) und Fabric API. JEI wird nur für „+“ und die Rezeptanzeige gebraucht und reicht bei den Spielern.

### Als Modrinth-Pack

`storage-dimension.mrpack` liegt in jedem [Release](https://github.com/Nomisimo/Storage-Dimension/releases). Das Pack enthält Fabric API (wird von Modrinth geladen) und die Storage-Dimension-Jar. Minecraft 26.1.2 und Fabric Loader 0.19.5 sind darin festgelegt. Spieler können dasselbe Pack in der Modrinth-App installieren.

- **Server-Hoster mit Modpack-Upload:** Neuen Server mit „Modpack hochladen / .mrpack“ anlegen und die Datei auswählen.
- **Eigener Server:** Mit [mrpack-install](https://github.com/nothub/mrpack-install): `mrpack-install storage-dimension.mrpack --server-dir server`. Das lädt Fabric-Server, Fabric API und legt die Mod ab.
