# VAG Monitor — Passat B8 1.6 TDI

Aplicație Android **separată de Prius Monitor**, dezvoltată exclusiv pe branch-ul `feature/vag-monitor`.
Pachet: `ro.vagmonitor.app`. Se poate instala lângă aplicația Prius, cu date și semnătură proprii.

## Prima versiune alpha

- Bluetooth clasic și BLE, inclusiv transporturile folosite pentru adaptorul VIECAR.
- Parametri OBD live detectați automat: turație, viteză, temperatură ulei și antigel, presiune rampă diesel, EGR, MAF, consum direct ECU și altele, numai când ECU le oferă.
- Catalog Passat cu filtrare după anul modelului, căutare și preset ulei/DPF. Definițiile comunitare necesită verificare pe mașină; nu reprezintă senzori garantați.
- Citire DTC motor și scanare UDS limitată la modulele listate; ștergere confirmată de utilizator, doar cu viteză și turație zero confirmate proaspăt.
- Raport de diagnoză distribuibil și identificarea calculatoarelor motor/instrumente.
- Monitorizare în fundal și ecran experimental Android Auto.

## Instalare și prima conectare

1. Instalează `VAG-Monitor-0.1.0-alpha.1.apk` din release-ul VAG. Nu înlocuiește aplicația Prius.
2. Închide conexiunea OBD din Prius Monitor / Car Scanner înainte de conectare.
3. Pune contactul, apasă **Conectare** și selectează adaptorul împerecheat sau **Caută Bluetooth BLE**.
4. În **Senzori**, alege anul modelului Passat B8 și, opțional, presetul ulei/DPF. Lipsa unui răspuns este afișată ca indisponibilă.
5. Pentru diagnoză folosește **Erori**. Pentru identificarea ECU folosește **Service**.

## Limite explicite

**Alpha pentru testare, fără validare fizică pe Passat/VIECAR.** Nu înseamnă acoperire completă a gamei VAG.
Partea comună acoperă OBD CAN 11-bit / 500 kbit/s; catalogul extins este numai pentru Passat.
K-line, TP2.0, DoIP și SFD nu sunt implementate. Datele expiră după 15 secunde; mulți senzori simultani reduc frecvența actualizării.

**Resetarea inspecției și a schimbului de ulei nu este implementată.** Este necesară identificarea calculatorului de bord și o procedură de adaptare verificată. Ștergerea DTC nu resetează service-ul.

**Android Auto este experimental și netestat pe unitatea mașinii.** Instalarea APK-ului nu garantează apariția în launcher-ul Android Auto; host-ul și regulile de distribuție pot bloca aplicația. Nu există aprobare Google Play pentru această versiune.

## Dezvoltare

JDK 17, Android SDK 34, Gradle wrapper inclus:

```powershell
cd app
.\gradlew.bat ktlintFormat
.\gradlew.bat check assembleDebug assembleDebugAndroidTest
```

Pentru APK semnat: configurație locală `vag-keystore.properties` și `assembleRelease`. Cheile nu se publică.
[Testele și sursele cercetate](docs/VAG-RESEARCH.md).

Cod de bază MIT, derivat din proiectul existent și [BETSY](https://github.com/alrighdee/BETSY).
Definițiile Passat: [OBDb contributors](https://github.com/OBDb/Volkswagen-Passat), CC BY-SA 4.0, licență separată descrisă în `docs/VAG-RESEARCH.md` și în asset-ul NOTICE.
