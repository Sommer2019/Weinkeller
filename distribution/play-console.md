# Google Play Console – Spickzettel

Paketname: `de.robin.wein` · Kategorie: **Essen & Trinken** · kostenlos · Zielgruppe ab 18

## Store-Eintrag (Hauptseite)
Texte und Grafiken liegen in `fastlane/metadata/android/de-DE/` (Format von fastlane/Google Play):

| Feld | Datei | Grenze |
|---|---|---|
| App-Name | `title.txt` | 30 Zeichen |
| Kurzbeschreibung | `short_description.txt` | 80 Zeichen |
| Vollständige Beschreibung | `full_description.txt` | 4000 Zeichen |
| App-Symbol 512 × 512 | `images/icon.png` | |
| Feature-Grafik 1024 × 500 | `images/featureGraphic.png` | |
| Smartphone-Screenshots | **selbst machen**, mind. 2 (Bestand, Scan, Wein-Details, Auswertung) | |

„Neuerungen“ für Tester: `distribution/whatsnew/whatsnew-de-DE` (max. 500 Zeichen, geht beim automatischen Upload mit).

## App-Inhalte (Richtlinien)

**Datenschutzerklärung:** `https://sommer2019.github.io/Weinkeller/datenschutz.html`
(GitHub → Settings → Pages → Source „Deploy from a branch“, Branch `main`, Ordner `/docs`. Vorher in
`docs/datenschutz.html` Name, Anschrift und E-Mail eintragen.)

**App-Zugriff:** Alle Funktionen ohne Anmeldung nutzbar.

**Werbung:** Nein.

**Einstufung des Inhalts** (Fragebogen, Kategorie „Alle anderen App-Typen“):
- Gewalt, Sexualität, Sprache, Glücksspiel: Nein
- Bezug auf Alkohol/Tabak/Drogen: **Ja – die App verwaltet Wein (keine Verkaufsplattform im eigenen Sinn)**
- Nutzer können miteinander interagieren / Inhalte teilen: Nein
- Teilt den Standort: Nein · Digitale Käufe: Nein

**Zielgruppe:** nur **18 und älter**. Spricht Kinder nicht an.

**Nachrichten-App:** Nein · **Gesundheit:** Nein · **Finanzfunktionen:** Keine · **Behörden-App:** Nein

## Datensicherheit
Google zählt alles, was das Gerät verlässt, als „erhoben“ – auch wenn es nicht an dich als Entwickler geht.

- Erhebt oder teilt die App Nutzerdaten? **Ja**
- Daten bei der Übertragung verschlüsselt? **Ja** (HTTPS; das NAS liegt im eigenen Heimnetz)
- Können Nutzer das Löschen anfordern? Es gibt kein Konto und keinen eigenen Server – Daten liegen nur auf dem
  Gerät/NAS des Nutzers und werden mit der App bzw. der Datei gelöscht.

| Datentyp | Erhoben | Geteilt | Optional | Zweck |
|---|---|---|---|---|
| Fotos und Videos → **Fotos** | Ja (nur bei „Automatisch einlesen“, an die Gemini-API) | Nein* | Ja | App-Funktionalität |

\* Gemini, Open Food Facts und UPCitemdb verarbeiten die Anfragen als Dienst für die App-Funktion.
Barcode-Nummern und E-Label-Links sind keine personenbezogenen Daten und werden nicht angegeben.
Kein Standort, keine Kontakte, keine Kennungen, keine Analysedaten.

## Release
1. Erste Version von Hand: Testen → **Interner Test** → Neuer Release → `…-PlayStore.aab` aus dem GitHub-Release
   hochladen, „Play App-Signatur“ akzeptieren, Tester-Liste (E-Mails) anlegen, Link verschicken.
2. Danach automatisch: Secret `PLAY_SERVICE_ACCOUNT_JSON` (Dienstkonto mit Release-Rechten) und – solange die App
   nie veröffentlicht war – Repo-Variable `PLAY_RELEASE_STATUS=draft`. Jeder Push auf `main` landet dann im internen Test.
3. Öffentlich: neue private Konten brauchen vorher einen **geschlossenen Test** (aktuell 12 Tester, 14 Tage),
   dann „Produktion“ beantragen.
