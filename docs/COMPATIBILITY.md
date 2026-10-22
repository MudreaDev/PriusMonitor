# Compatibilitate și verificare

Prius Monitor păstrează diferența dintre implementat software și validat fizic.

| Zonă | Verificat în această sesiune | Rămâne de confirmat |
| --- | --- | --- |
| Android 8 / API 26 | Pornire, navigare, Demo, jurnal SQLite, export CSV, rapoarte, recreare Activity și serviciu de fundal | Comportamentul pe telefonul utilizatorului |
| Android recent / API 37 | Versiunea de bază și serviciul de fundal, inclusiv scriere reală a unui export | Retestarea versiunii finale |
| OBD standard și Toyota | Decodoare, răspunsuri înregistrate/simulate, lipsă date, refuzuri, fluxul motorului aplicației | Prius ZVW30 și VIECAR fizic |
| Bluetooth Classic SPP | Integrare Android, anulare și termene de răspuns | Conectarea fizică și stabilitatea radio |
| BLE UART | Profiluri explicite, încadrare pe prompt, fragmentare, anulare și limite de memorie | GATT și ELM327 pe adaptor fizic |
| SOH | Afișare onestă ca indisponibil și observație pasivă | PID documentat sau test de capacitate validat |

Nu se folosesc răspunsuri Demo când o citire reală lipsește. Existența unui protocol BLE UART nu garantează că dispozitivul este ELM327; inițializarea verifică bannerul și comenzile adaptorului. Nu se încearcă scrieri pe caracteristici necunoscute.

Condițiile memorate la eroare (mode 02 / freeze frame) sunt afișate cu ECU-ul, cadrul și codul asociat, separat de citirile live. Citirea motorului este limitată la două cadre și la parametrii standard implementați din prima mască de suport. Răspunsurile tehnice sunt păstrate în raport; afișarea nu pretinde că toate cadrele Toyota au fost citite.

Surse pentru integrarea Android și profiluri, consultate la dezvoltare:

- [Permisiuni Bluetooth Android](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Conexiune și schimb de date GATT](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)
- [Serviciu de fundal connectedDevice](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)
- [Nordic UART Service — definiția producătorului](https://nrfconnectdocs.nordicsemi.com/ncs/latest/nrf/libraries/bluetooth/services/nus.html)
- [Implementare BLE ELM327 cu UUID-uri publicate de autor](https://github.com/tronikos/elm327_obdii_ble)
- [Profiluri observate de autorul obd-gauge-cluster](https://github.com/cheeseprince/obd-gauge-cluster/blob/main/docs/ADAPTERS.md)

Ultimele două surse descriu implementările și observațiile autorilor lor; nu constituie validare fizică pentru Prius Monitor. Implementarea Kotlin este proprie; nu s-a copiat cod din proiecte cu alte licențe. Protocolul Toyota și decodoarele de bază provin din BETSY, MIT, cu licența păstrată.
