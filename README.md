# Storage Dimension

Ein Fabric-Server-Mod für **Minecraft 26.1.2**. Spieler brauchen keinen Client-Mod, alles läuft auf dem Server (nur das optionale Traveler's-Backpack-Add-on muss auch bei den Spielern installiert sein).

**Download:** Die fertigen Dateien gibt es unter [Releases](https://github.com/Nomisimo/Storage-Dimension/releases). Dort steht auch, welche Datei wohin gehört.

## Was der Mod macht

- **Dimensionskiste:** Sieht aus wie eine ganz normale Kiste. Wer sie öffnet und Zugriff auf den Speicher hat, wird in den Lagerraum in der Storage Dimension gezogen. Es können beliebig viele Spieler gleichzeitig drin sein. Wer keinen Zugriff hat, sieht nur ein leeres Kisten-Inventar, in dem nichts gelagert werden kann.
- **ME-Speicher:** Die Enderkiste im Raum ist das Terminal. Dort liegen alle Items ohne Stapel- oder Slot-Grenzen. Die genaue Menge steht im Namen jedes Items (z. B. „Bruchstein × 1.234“), die Zahl auf dem Symbol geht bis 99.
  - Linksklick nimmt einen Stapel, Rechtsklick einen halben, Shift-Klick schiebt ihn ins Inventar.
  - Item auf dem Cursor in das Terminal klicken oder Shift-Klick im eigenen Inventar lagert ein.
  - Der Trichter-Knopf lagert das ganze Inventar (ohne Hotbar) ein.
  - **Suche:** Das Schild öffnet ein Eingabefeld. Text eintippen, dann auf das Ergebnisfeld rechts klicken. Rechtsklick auf das Schild löscht die Suche.
  - **Sortierung:** Der Komparator schaltet zwischen *Menge* und *Alphabet* um.
- **Hopper-Eingang:** Trichter, die in eine Dimensionskiste zeigen, liefern direkt in den Speicher des Besitzers. Zum Anbauen mit einem Item in der Hand schleichen.
- **Werkbank und Ofen** stehen im Raum, gecraftet wird direkt in der Dimension.
- **Gemeinsamer Speicher:** Standardmäßig teilen sich alle Spieler einen Speicher und einen Raum. Mit `"sharedStorage": false` in `config/storagedim.json` bekommt jeder seinen eigenen, der sich per `/storage teilen` freigeben lässt.
- **Filterkiste:** Eine Kiste, die nur bis zu 7 ausgewählte Items aus dem ME-Speicher zeigt, eins pro Rahmen im Rezept. Schleichen + Rechtsklick mit leerer Hand öffnet die Filter: ein Item auf einen Platz legen setzt den Filter, Klick mit leerer Hand entfernt ihn. Normaler Rechtsklick öffnet die Kiste, dort kann man die gefilterten Items herausnehmen und nur passende einlagern. Trichter liefern auch hier direkt in den Speicher.
- **ME-Terminal:** Eine extra Enderkiste. Hingestellt öffnet sie per Rechtsklick direkt den ME-Speicher, ohne Dimensionswechsel.
- **ME-Rucksack:** Ein Rucksack, der mit dem Speicher verbunden ist und ihn von überall öffnet.
  - **Bündel:** ME-Upgrade + Bündel in der Werkbank kombinieren. Rechtsklick öffnet den Speicher.
  - **[Traveler's Backpack](https://www.curseforge.com/minecraft/mc-mods/travelers-backpack-fabric) (optional):** ME-Upgrade in die Zweithand, den Rucksack in die Haupthand, schleichen und rechtsklicken. Danach öffnet Schleichen + Rechtsklick mit dem Rucksack den ME-Speicher, normaler Rechtsklick weiterhin den Rucksack. Trägst du ihn auf dem Rücken, öffnet `/storage me` den Speicher. Traveler's Backpack muss dafür auf Server und Clients installiert sein, Storage Dimension selbst bleibt rein serverseitig.
- **Ausgang:** Rechtsklick auf den Leuchtstein im Raum oder `/storage raus`.

## Rezept

```
A P A      A = Amethystscherbe
P C P      P = Enderperle
A P A      C = Kiste
```

ME-Terminal:

```
R A R      R = Redstone
A E A      A = Amethystscherbe
R A R      E = Enderkiste
```

Filterkiste:

```
F F F      F = Rahmen
F C F      C = Kiste
F E F      E = Enderauge
```

ME-Upgrade (formlos): Enderauge, 2× Amethystscherbe, Redstoneblock, Papier.
ME-Rucksack (formlos): beliebiges Bündel + ME-Upgrade.

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

## Bauen

Java 25 wird benötigt.

```
cd storage-dimension
./gradlew build
```

Die fertige Datei liegt in `storage-dimension/build/libs/`. GitHub Actions baut bei jedem Push automatisch und legt die Jars auf den Branch `builds`. Ein Release mit Anleitung entsteht per Hand unter *Actions → release → Run workflow*.

## Installation

1. Fabric Loader 0.19.5 oder neuer und Fabric API für 26.1.2 auf dem Server installieren.
2. Die `storage-dimension-*.jar` (nicht die `-sources.jar`) in den `mods`-Ordner legen.
3. Server starten. Die Dimension `storagedim:storage` wird automatisch angelegt.

Die Daten liegen in `world/storagedim.json`.

### Traveler's-Backpack-Add-on (optional)

Im Ordner `storagedim-tb/` liegt ein kleiner Zusatz-Mod. Er fügt ein echtes **ME-Upgrade** für den Upgrade-Slot von Traveler's Backpack hinzu.

- Rezept (formlos): Leeres Upgrade (Traveler's Backpack), Enderauge, 2× Amethystscherbe, Redstoneblock.
- Upgrade in einen Upgrade-Slot des Rucksacks legen. Linksklick auf das ME-Symbol im Rucksack-Fenster öffnet den ME-Speicher, Rechtsklick nimmt das Upgrade wieder heraus.
- Der Rucksack muss nicht in der Hand sein: auf dem Rücken tragen und mit der Rucksack-Taste von Traveler's Backpack (Standard **B**) öffnen reicht.
- **Crafting aus dem ME-Speicher:** Hat der Rucksack zusätzlich das Crafting-Upgrade, holt der „+“-Knopf von [JEI](https://modrinth.com/mod/jei) fehlende Zutaten auch aus dem ME-Speicher, genauso wie aus Inventar und Rucksack. Shift + „+“ füllt das Raster für so viele Durchgänge wie möglich (bis zu einem Stapel). Was vorher im Raster lag, kommt zurück ins Inventar oder, wenn das voll ist, in den Speicher.
  - Der kleine Schalter neben dem ME-Symbol stellt zwischen *ME als Quelle* (Standard) und *nur Inventar* um. Auf *nur Inventar* arbeitet „+“ wie ohne Upgrade.
- Installation: `storagedim-tb-*.jar` **auf dem Server und bei allen Spielern**, zusammen mit Traveler's Backpack (und dessen Abhängigkeiten) und Fabric API. Storage Dimension selbst muss auf dem Server laufen. JEI wird nur für „+“ gebraucht und reicht bei den Spielern.

### Als Modrinth-Pack

`storage-dimension.mrpack` liegt in jedem [Release](https://github.com/Nomisimo/Storage-Dimension/releases). Das Pack enthält Fabric API (wird von Modrinth geladen) und die Storage-Dimension-Jar. Minecraft 26.1.2 und Fabric Loader 0.19.5 sind darin festgelegt.

- **Server-Hoster mit Modpack-Upload:** Neuen Server mit „Modpack hochladen / .mrpack“ anlegen und die Datei auswählen.
- **Eigener Server:** Mit [mrpack-install](https://github.com/nothub/mrpack-install): `mrpack-install storage-dimension.mrpack --server-dir server`. Das lädt Fabric-Server, Fabric API und legt die Mod ab.
