# Storage Dimension

Ein Fabric-Server-Mod für **Minecraft 26.1.2**. Spieler brauchen keinen Client-Mod, alles läuft auf dem Server.

## Was der Mod macht

- **Dimensionskiste:** Sieht aus wie eine ganz normale Kiste. Öffnet der Besitzer sie, wird er in seinen eigenen Lagerraum in der Storage Dimension gezogen.
- **Zuschauen:** Öffnet ein anderer Spieler die Kiste, während der Besitzer drin ist, schaut er für bis zu 30 Sekunden als Zuschauer in den Raum und sieht ihn herumlaufen. Schleichen bringt ihn zurück. Ist niemand drin, zeigt die Kiste nur ein leeres Kisten-Inventar, in dem nichts gelagert werden kann.
- **ME-Speicher:** Die Enderkiste im Raum ist das Terminal. Dort liegen alle Items sortiert nach Menge, ohne Stapel- oder Slot-Grenzen.
  - Linksklick nimmt einen Stapel, Rechtsklick einen halben, Shift-Klick schiebt ihn ins Inventar.
  - Item auf dem Cursor in das Terminal klicken oder Shift-Klick im eigenen Inventar lagert ein.
  - Der Trichter-Knopf lagert das ganze Inventar (ohne Hotbar) ein.
- **Hopper-Eingang:** Trichter, die in eine Dimensionskiste zeigen, liefern direkt in den Speicher des Besitzers. Zum Anbauen mit einem Item in der Hand schleichen.
- **Werkbank und Ofen** stehen im Raum, gecraftet wird direkt in der Dimension.
- **Gemeinsamer Speicher:** Standardmäßig teilen sich alle Spieler einen Speicher und einen Raum. Mit `"sharedStorage": false` in `config/storagedim.json` bekommt jeder seinen eigenen, der sich per `/storage teilen` freigeben lässt.
- **ME-Terminal:** Eine extra Enderkiste. Hingestellt öffnet sie per Rechtsklick direkt den ME-Speicher, ohne Dimensionswechsel.
- **ME-Rucksack:** Ein ME-Upgrade mit einem beliebigen Bündel kombinieren. Rechtsklick mit dem Rucksack öffnet den Speicher von überall.
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
| `/storage give [spieler]` | (OP) Gibt eine Dimensionskiste |
| `/storage give terminal` | (OP) Gibt ein ME-Terminal |

## Bauen

Java 25 wird benötigt.

```
cd storage-dimension
./gradlew build
```

Die fertige Datei liegt in `storage-dimension/build/libs/`. GitHub Actions baut bei jedem Push automatisch, die .jar gibt es dann unter *Actions → Artifacts*.

## Installation

1. Fabric Loader 0.19.5 oder neuer und Fabric API für 26.1.2 auf dem Server installieren.
2. Die `storage-dimension-*.jar` (nicht die `-sources.jar`) in den `mods`-Ordner legen.
3. Server starten. Die Dimension `storagedim:storage` wird automatisch angelegt.

Die Daten liegen in `world/storagedim.json`.

### Als Modrinth-Pack

GitHub Actions baut bei jedem Push auch `storage-dimension.mrpack` (im Artifact *storage-dimension*). Das Pack enthält Fabric API (wird von Modrinth geladen) und die Storage-Dimension-Jar. Minecraft 26.1.2 und Fabric Loader 0.19.5 sind darin festgelegt.

- **Server-Hoster mit Modpack-Upload:** Neuen Server mit „Modpack hochladen / .mrpack“ anlegen und die Datei auswählen.
- **Eigener Server:** Mit [mrpack-install](https://github.com/nothub/mrpack-install): `mrpack-install storage-dimension.mrpack --server-dir server`. Das lädt Fabric-Server, Fabric API und legt die Mod ab.
