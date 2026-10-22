# Prius Monitor

Aplicație Android în română pentru Toyota Prius 30 / ZVW30, bazată pe [BETSY](https://github.com/alrighdee/BETSY), cu adaptor ELM327 Bluetooth Classic SPP sau BLE UART acceptat.

## Funcții

- Dashboard cu viteză, turație, temperatură lichid de răcire, SOC HV, tensiune 12 V și grafice.
- Consum instantaneu și mediu, l/h separat, litri și timp la viteză 0, distanță, durata călătoriei și sugestii explicate.
- Baterie: 14 blocuri, tensiuni, diferență max–min, rezistențe interne, temperaturi, curent și putere calculată.
- Sănătate / SOH: informații despre limitele măsurării și înregistrare pasivă a valorilor HV. Fără procent de sănătate inventat.
- Diagnosticare motor și Toyota hibrid, explicații în română pentru codurile uzuale, condiții memorate la eroare (freeze frame), monitoare emisii, rapoarte locale și export cu răspunsurile tehnice ECU.
- Bluetooth Classic și căutare/conexiune BLE UART, permisiuni Android, limite de timp, deconectare și erori explicite.
- Ecran Live compact cu 15 indicatori, fără derulare, accesibil din bara de jos; setări pentru unități și intervalul de actualizare. Modul Demo a fost eliminat.

## Instalare

1. Deschide APK-ul pe un telefon cu Android 8.0 sau mai nou; permite instalarea din sursa respectivă dacă Android o cere.
2. Introdu adaptorul în portul OBD și asociază-l în setările Bluetooth ale telefonului, folosind PIN-ul indicat de adaptor.
3. Pregătește mașina pentru citirea ECU (de regulă, READY), cu mașina în siguranță.
4. Închide Car Scanner și alte aplicații OBD care pot ocupa adaptorul.
5. Deschide Conexiune în Prius Monitor, acordă accesul Bluetooth și selectează VIECAR.
6. Dacă apare o eroare, folosește „Copiază eroarea” din Conexiune pentru a păstra mesajul complet.

Permisiunea Bluetooth trebuie acordată pe telefon la prima utilizare. Nu sunt necesare cont, internet, abonament sau Play Store pentru utilizare. Pentru BLE, folosește căutarea de 15 secunde din Conexiune; sunt implementate profilurile FFF0/FFF1/FFF2, FFE0/FFE1, 18F0/2AF0/2AF1 și Nordic UART. Se verifică proprietățile caracteristicilor și inițializarea ELM327; nu se scrie în caracteristici arbitrare. BLE necesită validare pe adaptorul fizic. Android 8–11 cere acces Locație și localizarea activă pentru căutare; aplicația nu citește poziția GPS. Android 12+ cere Dispozitive din apropiere. Căutarea se oprește când părăsești ecranul/aplicația. Classic nu cere permisiunea Locație.

## Depanare VIECAR

Dacă inițializarea ATZ / ATE0 primește un răspuns hexazecimal de la ECU, noua citire ignoră acele cadre vechi și așteaptă răspunsul real la comanda de configurare. Legătura Classic trimite întâi un spațiu pentru a întrerupe o citire anterioară și așteaptă 300 ms fără trafic; nu repetă ultima comandă necunoscută printr-un Enter. Timpul și volumul datelor de sincronizare sunt limitate.

Închide complet Car Scanner și celelalte aplicații OBD, scoate și reintrodu adaptorul, apoi încearcă Bluetooth Classic SPP. Dacă eșuează, copiază eroarea din Conexiune. Un răspuns nepotrivit nu este tratat drept conectare reușită.

Pentru profilurile BLE UART cunoscute, refuzul CCCD cu status ATT 3 / 4 / 6 permite verificarea notificărilor deja active, după activarea rutării locale. Conectarea nu este confirmată înainte de răspunsurile ELM327 și inițializarea citirii ECU. Erorile de autentificare, criptare, deconectare sau expirare a timpului nu folosesc această excepție. Compatibilitatea necesită test fizic.

## Consum și călătorii

Debitul este citit direct din PID 015E dacă ECU îl oferă. Altfel, cu MAF și turație disponibile, se estimează pentru benzină: l/h = MAF[g/s] × 3600 / (14,7 × 745). Estimarea este etichetată; nu identifică sigur tăierea injecției și poate diferi de bord. La turație citită de 0 rpm, consumul de benzină este considerat 0. Energia electrică nu este inclusă.

l/100 km = l/h × 100 / km/h, afișat de la 5 km/h. Media integrează debitul și viteza pe intervalele valide, include opririle și apare după minimum 100 m cu date de consum. Lipsa datelor și intervalele mai lungi de 15 secunde nu creează valori fictive. Acoperirea calculului este afișată.

**Viteză 0 nu înseamnă Parking.** Litrii și timpul la viteză 0 sunt separați, iar călătoria continuă.

Cu monitorizarea în fundal activată (implicit), o conexiune reală poate continua când folosești altă aplicație sau stingi ecranul. Serviciul afișează o notificare cu buton de oprire și menține procesorul activ pe durata conexiunii, în limita unei solicitări de 12 ore. Acest mod poate crește consumul telefonului. Schimbarea setării se aplică la următoarea conexiune. La deconectare, ultimul rezumat rămâne exportabil din Setări. O conexiune nouă începe o călătorie nouă.

Istoricul salvează automat eșantioanele OBD și rezumatul fiecărei călătorii într-un jurnal SQLite privat. Sunt disponibile grafice și export CSV cu unități explicite, marcaj Demo și câmpuri goale pentru citirile indisponibile. O sesiune întreruptă de oprirea procesului este identificată la următoarea pornire. Rapoartele de diagnostic rămân în spațiul privat al aplicației după repornire; dezinstalarea le șterge. Nu se trimit automat date sau scanări pe internet.

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

Runner-ul verifică ecranul Live fără derulare, navigarea fără Demo, valorile dintr-un transport de test inclus exclusiv în APK-ul de test, păstrarea ecranului la actualizare, erorile de conexiune, exportul și recrearea activității. La succes afișează ALL ... DEVICE CHECKS PASSED.

GitHub Actions produce artifactul PriusMonitor-debug-apk după verificări. APK-urile debug produse în medii diferite pot avea semnături diferite și pot necesita dezinstalarea versiunii vechi înaintea instalării.

## Licență

BETSY: licență MIT; LICENSE și NOTICE sunt păstrate. Documentația originală: [README-BETSY.md](README-BETSY.md), [protocol](docs/PROTOCOL.md). Prius Monitor nu este afiliat oficial cu Toyota.

Observația pasivă păstrează min/max pentru fiecare bloc, contextul diferenței maxime și curentul înregistrat. Fluxul absolut de sarcină (Ah) și energie (kWh) este aproximat prin integrare pe timpul monoton al telefonului, numai între citiri valide la cel mult 15 secunde. Schimbarea sensului între două citiri este tratată explicit. Ambele sensuri se cumulează în modul: nu sunt consum net, capacitate sau SOH. Documentația de bază are mențiuni neuniforme despre polaritatea curentului; interfața păstrează semnul numeric și cere validarea sensului pe mașină, fără etichete neconfirmate de încărcare/descărcare.

Din Setări poate fi activat un jurnal tehnic ELM, oprit implicit. Înregistrează comenzile și răspunsurile adaptorului pentru verificarea compatibilității pe mașină. Ultimele 10 sesiuni sunt păstrate în spațiul privat al aplicației, maximum 4 MB / 50.000 de linii pe sesiune, apoi captura se oprește fără să oprească citirea OBD. Nu se trimite nimic automat. Exportul pregătește o copie a fișierului în fundal și folosește selectorul Android; conținutul unui export în timpul conexiunii reprezintă un snapshot al jurnalului.
