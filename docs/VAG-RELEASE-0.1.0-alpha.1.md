# VAG Monitor 0.1.0-alpha.1

Aplicație Android separată pentru dezvoltarea diagnozei VAG, pe branch-ul `feature/vag-monitor`.
Pachet `ro.vagmonitor.app`, semnătură proprie; poate fi instalată lângă Prius Monitor.
Acest prerelease nu înlocuiește release-ul Prius marcat Latest.

## Inclus

- Conectare ELM327 Bluetooth clasic / BLE și monitorizare în fundal.
- 28 decodoare standard OBD, cu detectarea suportului ECU: temperatură ulei, antigel,
  turație, viteză, EGR, presiune rampă diesel, MAF, consum direct și alte valori.
- Catalog offline Passat, filtrat după anul modelului; preset ulei/DPF și selectarea
  a maximum 12 cereri VAG suplimentare. Datele lipsă sau expirate sunt marcate explicit.
- Citire erori motor și scanare UDS a șase module; ștergere OBD sau individuală UDS,
  cu confirmarea utilizatorului și verificarea vitezei/turației zero.
- Identificare ECU motor/instrumente, raport distribuibil și ecran experimental Android Auto.

## Limite înainte de instalare

**Este o versiune alpha, netestată fizic pe Passat B8 1.6 TDI sau pe adaptorul VIECAR.**
Nu oferă încă toți senzorii și toate protocoalele întregii game VAG. Catalogul extins este
Passat-specific; suportul generic acoperă OBD CAN 11-bit, 500 kbit/s.

**Resetarea inspecției și a service-ului de ulei nu este implementată.** Ecranul Service
oferă identificarea ECU necesară pentru dezvoltarea unei proceduri verificate.

**Android Auto rămâne experimental.** Nu este verificat pe head-unit și nu are aprobare
Google Play; APK-ul poate fi ascuns/refuzat de host. Nu se garantează apariția în launcher.

Închide conexiunea din Prius Monitor / Car Scanner, pune contactul, conectează adaptorul
în VAG Monitor, apoi alege anul modelului în Senzori. Pentru calibrarea suportului real,
trimite raportul obținut din Service → Identifică ECU → Erori → Distribuie raportul.

## Verificări

Build semnat reușit, 246 teste unitare debug trecute, teste unitare release trecute,
zero erori Android lint și nouă verificări instrumentate pe Android 8/API26.
Emulatorul Android modern a fost blocat de resursele locale; nu este declarat testat.

Definiții Passat: OBDb contributors, CC BY-SA 4.0. Cod: MIT. Proveniența și sursele
sunt documentate în `docs/VAG-RESEARCH.md` și în NOTICE-ul inclus în aplicație.
