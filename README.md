# Prius Monitor

Aplicație Android în română pentru Toyota Prius 30 / ZVW30, bazată pe [BETSY](https://github.com/alrighdee/BETSY), cu adaptor ELM327 Bluetooth Classic SPP (inclusiv VIECAR cu acest profil).

## Funcții

- Dashboard cu viteză, turație, temperatură lichid de răcire, SOC HV, tensiune 12 V și grafice.
- Consum instantaneu și mediu, l/h separat, litri și timp la viteză 0, distanță, durata călătoriei și sugestii explicate.
- Baterie: 14 blocuri, tensiuni, diferență max–min, rezistențe interne, temperaturi, curent și putere calculată.
- Sănătate / SOH: informații despre limitele măsurării și înregistrare pasivă a valorilor HV. Fără procent de sănătate inventat.
- Diagnosticare motor și Toyota hibrid, explicații în română pentru codurile uzuale, rapoarte locale și export cu răspunsurile tehnice ECU.
- Bluetooth cu permisiuni Android, limite de timp, deconectare și erori explicite.
- Demo separat, etichetat permanent; setări pentru unități și intervalul de actualizare.

## Instalare

1. Deschide APK-ul pe un telefon cu Android 8.0 sau mai nou; permite instalarea din sursa respectivă dacă Android o cere.
2. Introdu adaptorul în portul OBD și asociază-l în setările Bluetooth ale telefonului, folosind PIN-ul indicat de adaptor.
3. Pregătește mașina pentru citirea ECU (de regulă, READY), cu mașina în siguranță.
4. Închide Car Scanner și alte aplicații OBD care pot ocupa adaptorul.
5. Deschide Conexiune în Prius Monitor, acordă accesul Bluetooth și selectează VIECAR.
6. Fără mașină, folosește Pornește modul Demo. Datele Demo sunt simulate.

Permisiunea Bluetooth trebuie acordată pe telefon la prima utilizare. Nu sunt necesare cont, internet, abonament sau Play Store pentru utilizare. Adaptoarele exclusiv BLE nu sunt acceptate de acest build.

## Consum și călătorii

Debitul este citit direct din PID 015E dacă ECU îl oferă. Altfel, cu MAF și turație disponibile, se estimează pentru benzină: l/h = MAF[g/s] × 3600 / (14,7 × 745). Estimarea este etichetată; nu identifică sigur tăierea injecției și poate diferi de bord. La turație citită de 0 rpm, consumul de benzină este considerat 0. Energia electrică nu este inclusă.

l/100 km = l/h × 100 / km/h, afișat de la 5 km/h. Media integrează debitul și viteza pe intervalele valide, include opririle și apare după minimum 100 m cu date de consum. Lipsa datelor și intervalele mai lungi de 15 secunde nu creează valori fictive. Acoperirea calculului este afișată.

**Viteză 0 nu înseamnă Parking.** Litrii și timpul la viteză 0 sunt separați, iar călătoria continuă.

La deconectare sau trecerea în fundal, conexiunea se închide și ultimul rezumat al călătoriei este păstrat local, exportabil din Setări. Exportul prin selectorul de documente păstrează sesiunea activă. O conexiune nouă începe o călătorie nouă.

Rapoartele de diagnostic rămân în spațiul privat al aplicației după repornire; dezinstalarea le șterge. Nu se trimit automat date sau scanări pe internet.

## Limite

Gen 3 este implementat conform decodoarelor BETSY și verificat software. **Nu este declarat validat pe Prius-ul utilizatorului.** Adaptorul și ECU-ul pot refuza parametri, afișați ca — sau note în raport. Nu se substituie valori Demo, zero sau citiri vechi expirate.

Profilul HV vizează pachetul Prius cu 14 blocuri. Nu este un scanner universal pentru orice Toyota. Un rezultat fără coduri nu certifică toate ECU-urile. SOC este încărcarea curentă, nu SOH. Fără un PID SOH documentat sau un test de capacitate validat, SOH rămâne indisponibil. Observațiile tensiunilor nu certifică sănătatea bateriei.

Aplicația citește date. Nu șterge erori și nu include codare, programare, teste de actuatori ori configurarea luminilor.

## Compilare și teste

Necesită JDK 17 și Android SDK 34. Configurează app/local.properties cu sdk.dir sau ANDROID_HOME. Din app:

```text
./gradlew check assembleDebug assembleDebugAndroidTest --no-daemon
```

Pe Windows: gradlew.bat. check execută teste unitare, ktlint și Android Lint. APK: app/build/outputs/apk/debug/PriusMonitor-debug.apk.

Cu un emulator pornit, din app:

```text
adb install -r build/outputs/apk/debug/PriusMonitor-debug.apk
adb install -r build/outputs/apk/androidTest/debug/PriusMonitor-debug-androidTest.apk
adb shell am instrument -w ro.priusmonitor.app.test/org.betsy.monitor.MonitorInstrumentation
```

Runner-ul verifică pornirea, Demo, Consum, cele 14 blocuri, scanarea și salvarea rapoartelor, setările și deconectarea. La succes afișează ALL ... DEVICE CHECKS PASSED.

GitHub Actions produce artifactul PriusMonitor-debug-apk după verificări. APK-urile debug produse în medii diferite pot avea semnături diferite și pot necesita dezinstalarea versiunii vechi înaintea instalării.

## Licență

BETSY: licență MIT; LICENSE și NOTICE sunt păstrate. Documentația originală: [README-BETSY.md](README-BETSY.md), [protocol](docs/PROTOCOL.md). Prius Monitor nu este afiliat oficial cu Toyota.