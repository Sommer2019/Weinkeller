# Weinkeller (Android & Windows)

Weinbestand per Barcode pflegen. Die Liste ist eine Excel-Datei (`.xlsx`), die die App selbst anlegt –
auf dem NAS (Samba) oder als Datei auf dem Handy bzw. PC. Handy und PC können dieselbe Datei auf dem NAS benutzen.

## Bauen
Android Studio → Projekt öffnen → Run, oder `./gradlew assembleRelease`.

## Ablauf
1. Barcode scannen (oder EAN eintippen). Die App sucht online (Open Food Facts, sonst UPCitemdb).
2. Neu: Name, Weingut, Land, Farbe werden vorgeschlagen. Jahrgang wird nur übernommen, wenn er eindeutig im Text steht –
   sonst muss er eingetragen werden (oder „ohne Jahrgang“).
3. Danach direkt Einkauf buchen: Anzahl, Preis je Flasche, Datum (heute, änderbar).
4. Schon vorhanden (gleiche EAN): „+ Kauf“ / „− Verkauf“ oder „Anderer Jahrgang“.

Ohne Barcode: im Bestand „Von Hand“ (oder im Scan-Tab „Kein Barcode? Wein von Hand anlegen“) – alle Felder selbst
eintragen, die EAN bleibt leer. Die App vergibt dann intern eine eigene Nummer.

Dieselbe EAN gilt oft für mehrere Jahrgänge – ein Wein ist deshalb immer EAN + Jahrgang.

## Online-Quellen & Etikett
Reihenfolge der Suche: Open Food Facts → UPCitemdb → KI-Recherche (optional) → EAN-Search (optional, kostenpflichtig).
Menü → „Online-Quellen“:
- **Gemini API-Schlüssel** (kostenlos, aistudio.google.com/apikey, ohne Kreditkarte): Gemini Flash (fest: gemini-3.6-flash) sucht per
  Google nach der EAN (Händler-/Winzerseiten) und kann das Etikett-Foto lesen. Gratis-Kontingent laut Google:
  bis 500 Anfragen mit Google-Suche pro Tag. Im Gratis-Zugang darf Google die Anfragen (EAN, Foto) zur
  Produktverbesserung verwenden. Läuft automatisch, wenn die freien Datenbanken nichts finden, und per Knopf
  „KI-Recherche (mit Etikett)“ im Anlegen-Dialog. Ergebnisse füllen nur leere Felder, Quellen werden angezeigt.
- **EAN-Search-Token** (optional, kostenpflichtig). Findet EAN-Search die Flasche nicht, schlägt es über das Hersteller-Präfix den Erzeuger vor.

Im Anlegen-Dialog: „Etikett fotografieren“ oder „Aus Galerie“. Die Texterkennung läuft offline und füllt nur leere Felder:
größte Schrift = Name (+ Prädikat wie „Spätlese trocken“), Zeile mit Weingut/Domaine/Château … = Erzeuger,
Rebsorte und Region per Wortliste. Stehen mehrere Jahreszahlen drauf, wählst du den Jahrgang per Antippen.

## Inserieren
Weindetails → „Auf Kleinanzeigen / eBay inserieren“: Titel und Beschreibung werden aus den Stammdaten erzeugt
(Menge, Preis, Alkohol, Füllmenge, „Enthält Sulfite“, Privatverkauf-Hinweis, ab 18) und sind frei änderbar.
Beide Plattformen haben keine Schnittstelle zum Vorausfüllen – die App kopiert den Text in die Zwischenablage,
legt das Flaschenbild in die Galerie (Ordner „Weinkeller“) und öffnet die App bzw. Webseite. Oder „Teilen …“.
Verkauft? Wie gewohnt „− Verkauf“ buchen.

## Bilder
Findet die Online-Suche ein Produktfoto, wird es beim Wein gespeichert (Spalte „Bild“) und auf dem Gerät
zwischengespeichert – danach auch offline sichtbar. Im Bearbeiten-Dialog lässt es sich entfernen.

## Auswertung
Tab „Auswertung“ mit zwei Sichtweisen:
- **Kasse**: Verkauf − Einkauf. Einkäufe zählen sofort negativ – zeigt, was an Geld rein und raus ging.
- **Gewinn**: Verkaufspreis minus Einkaufspreis der verkauften Flaschen (FIFO: zuerst gekaufte gehen zuerst raus).
  Einkäufe zählen erst, wenn die Flasche verkauft ist – volle Kellerregale sind kein Verlust.

Ansicht nach Monaten, Jahren oder als Kalender (Tage eingefärbt, antippen zeigt die Buchungen).
Zeitraum: alles, dieses/letztes Jahr, 12 Monate oder frei wählbar.
Verkäufe ohne Preis zählen als Eigenverbrauch; fehlt der Einkaufspreis, wird der Verkauf nicht im Gewinn gezählt.

## Datei
- Blatt **Weine**: Stammdaten; Bestand, Ø Einkauf, Verkauft, Umsatz als Formeln.
- Blatt **Bewegungen**: jede Buchung eine Zeile (Datum, Art, Menge, Preis/Flasche, Summe, Notiz, Gewinn).
- Blatt **Auswertung**: Einkauf, Verkauf, Gewinn/Verlust, Flaschen rein/raus je Monat (von der App berechnet).
- Nichts wird automatisch gelöscht: Weine mit Bestand 0 bleiben, Buchungen verschwinden nur per Hand.
- Eigene Zusatzspalten im Blatt „Weine“ bleiben erhalten; Formatierung macht die App beim Speichern neu.

## Windows-Version

Ordner `desktop/` – dieselbe Fachlogik (Excel-Datei, Bestand, Auswertung) und Oberfläche wie auf dem Handy,
als Windows-Programm (Compose for Desktop). Unterschiede zum Handy:
- **Scannen** per USB-/Bluetooth-Barcodescanner (der „tippt“ die EAN samt Enter ins Feld) oder Eintippen. Keine Kamera.
- **Etikett**: keine Texterkennung auf dem PC – ein Etikett-Foto (Datei) kann an die KI-Recherche gehen.
- **Inserieren**: Text in die Zwischenablage, Bild nach `Bilder\Weinkeller`, Webseite öffnet im Browser.
- Einstellungen, Arbeitskopie und Bilder liegen in `%APPDATA%\Weinkeller`; Passwörter mit der Windows-Datenschutz-API verschlüsselt.
- Strg+S speichert; beim Schließen mit ungespeicherten Änderungen fragt das Programm nach.

Bauen (braucht JDK 17+): `./gradlew -p desktop run` zum Starten, `./gradlew -p desktop packageMsi` für den Installer
(nur unter Windows). Die Pipeline baut beides automatisch: `.msi` (Installation ohne Admin-Rechte, Updates ersetzen
die alte Version) und `.zip` (entpacken, `Weinkeller.exe` starten).

## GitHub & automatischer APK-Bau

Die Pipeline (`.github/workflows/android.yml`) baut bei jedem Push auf `main` ein Release-APK und die Windows-Version
(Actions → letzter Lauf → Artifacts) und legt ein GitHub-Release mit APK, MSI und ZIP an. Ein Tag `v…` ebenso.

Einmalig hochladen (GitHub CLI):

    git init -b main && git add . && git commit -m "Erste Version"
    gh repo create weinkeller --private --source . --push

Signierschlüssel (einmalig, damit Updates sich über die installierte App legen lassen):

    keytool -genkeypair -v -keystore release.jks -alias app -keyalg RSA -keysize 4096 -validity 36500
    gh secret set KEYSTORE_BASE64 < <(base64 -w0 release.jks)
    gh secret set KEYSTORE_PASSWORD
    gh secret set KEY_ALIAS --body app
    gh secret set KEY_PASSWORD

`release.jks` gut aufheben (nicht ins Repo – steht in .gitignore). Ohne die Secrets baut die Pipeline trotzdem,
signiert dann aber mit einem wechselnden Debug-Schlüssel.

Neue Version veröffentlichen:

    git tag v1.1 && git push --tags
