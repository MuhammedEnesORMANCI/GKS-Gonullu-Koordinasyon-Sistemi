# Use Case Diagram

> **Gösterim:** Mermaid'de UML use case sözdizimi yoktur. Aktörler dikdörtgen, kullanım senaryoları yuvarlatılmış düğüm, sistem sınırı çerçeve olarak çizildi. **Kesik çerçeveli** senaryolar Genişletilmiş MVP'dir (zaman kalırsa, KP3). R2 ve sonrası **bilinçli olarak gösterilmez.** Yetkiler `docs/analysis/user-roles.md` §4 ile birebir eşleşir. Okunabilirlik için diyagram üç görünüme bölündü.

Aktörler: **ADM** Kurum Yöneticisi · **KOR** Operasyon Koordinatörü · **LDR** Grup Lideri · **GON** Gönüllü · **TEK** Teknik Sistem Yöneticisi · **UYM** Uyum/Denetçi · **FCM** (ikincil sistem aktörü)

## Görünüm 1 — Hesap ve erişim

```mermaid
flowchart LR
  ADM["Kurum Yöneticisi (ADM)"]
  KOR["Koordinatör (KOR)"]
  TEK["Teknik Yönetici (TEK)"]
  UYM["Uyum / Denetçi (UYM)"]
  LDR["Grup Lideri (LDR)"]
  GON["Gönüllü (GON)"]

  subgraph SYS1["Gönüllü Koordinasyon Sistemi — Hesap ve erişim"]
    UC01(["UC-01 Web paneline giriş + TOTP"])
    UC02(["UC-02 Android girişi"])
    UC03(["UC-03 Daveti kabul et, hesabı etkinleştir"])
    UC04(["UC-04 Kullanıcı davet et"])
    UC05(["UC-05 Rol ve kapsam ata / kaldır"])
    UC06(["UC-06 Hesabı devre dışı bırak"])
    UC07(["UC-07 Oturumları iptal et"])
    UC08(["UC-08 Parola sıfırla (yönetici)"])
    UC09(["UC-09 MFA sıfırla"])
    UC10(["UC-10 Profilini yönet"])
    UC11(["UC-11 Tüm cihazlardan çık"])
    UC12(["UC-12 Kendi parolasını sıfırla"]):::ext
    UC13(["UC-13 Aydınlatma metnini onayla"]):::ext
    UC14(["UC-14 Hesap kapatma talebi"]):::ext
  end

  ADM --- UC01
  KOR --- UC01
  TEK --- UC01
  UYM --- UC01
  GON --- UC02
  LDR --- UC02
  GON --- UC03
  ADM --- UC04
  KOR --- UC04
  ADM --- UC05
  ADM --- UC06
  TEK --- UC06
  ADM --- UC07
  TEK --- UC07
  ADM --- UC08
  ADM --- UC09
  GON --- UC10
  LDR --- UC10
  GON --- UC11
  LDR --- UC11
  GON --- UC12
  LDR --- UC12
  GON --- UC13
  GON --- UC14
  LDR --- UC14
  ADM --- UC14

  classDef ext stroke-dasharray: 5 5
```

**Açıklama:** Hesap yalnızca davetle oluşur (K-06). Davet kurum genelinde ADM, kendi operasyonu için KOR tarafından gönderilir. Web girişinde ADM ve KOR için TOTP zorunludur (K-19). TEK yalnızca teknik gerekçeyle hesap devre dışı bırakma ve oturum iptali yapar ve iş verisi görmez (TD-14). UC-10 ve UC-11 web rollerince de kullanılır (WEB-12); görünüm sade kalsın diye yalnızca mobil rollere bağlandı. UC-14'ü gönüllü ve lider talep eder, ADM işler.

## Görünüm 2 — Operasyon, grup, görev ve konum

```mermaid
flowchart LR
  ADM["Kurum Yöneticisi (ADM)"]
  KOR["Koordinatör (KOR)"]
  LDR["Grup Lideri (LDR)"]
  GON["Gönüllü (GON)"]

  subgraph SYS2["Gönüllü Koordinasyon Sistemi — Operasyon ve saha"]
    UC20(["UC-20 Operasyon oluştur, KOR ata"])
    UC21(["UC-21 Operasyonu aktif et / kapat"])
    UC22(["UC-22 Operasyonu askıya al"]):::ext
    UC23(["UC-23 Operasyon davetini kabul et (TD-01)"])
    UC24(["UC-24 Grup oluştur (lider zorunlu)"])
    UC25(["UC-25 Üye ekle / çıkar, lider değiştir"])
    UC26(["UC-26 Grup bilgisi ve toplanma noktasını güncelle"])
    UC27(["UC-27 Grubu ve toplanma noktasını görüntüle"])
    UC28(["UC-28 Görev oluştur, ata, kapasite belirle"])
    UC29(["UC-29 Görev durumunu ilerlet / iptal et, atama kaldır"])
    UC30(["UC-30 Görevi kabul / ret et"])
    UC31(["UC-31 Görev ve kapasite durumunu izle"])
    UC32(["UC-32 Konum paylaşımını başlat / durdur"])
    UC33(["UC-33 Son konumları görüntüle"])
  end

  ADM --- UC20
  ADM --- UC21
  KOR --- UC21
  ADM --- UC22
  KOR --- UC22
  GON --- UC23
  KOR --- UC24
  KOR --- UC25
  KOR --- UC26
  LDR --- UC26
  GON --- UC27
  LDR --- UC27
  KOR --- UC28
  KOR --- UC29
  GON --- UC30
  LDR --- UC30
  KOR --- UC31
  ADM -. "salt-okur" .- UC31
  GON --- UC32
  LDR --- UC32
  KOR --- UC33

  classDef ext stroke-dasharray: 5 5
```

**Açıklama:** Operasyonu yalnızca ADM oluşturur; aktif etme ve kapatmayı ADM ile operasyonun KOR'u yapar (FR-19). Grup, görev ve üye işlemleri yalnızca KOR'dadır. ADM bunları ancak kendini o operasyona KOR olarak atayarak yapar (K-07), bu yüzden ADM'den bu senaryolara bağlantı çizilmedi. Grup lideri yalnızca grup bilgisini ve toplanma noktasını günceller (K-08); üye ekleyemez, görev yönetemez, konum göremez (K-15). Konumu yalnızca aktif operasyonun KOR'u görür ve her görüntüleme denetime yazılır (BR-08).

## Görünüm 3 — Duyuru, acil alarm ve denetim

```mermaid
flowchart LR
  ADM["Kurum Yöneticisi (ADM)"]
  KOR["Koordinatör (KOR)"]
  UYM["Uyum / Denetçi (UYM)"]
  LDR["Grup Lideri (LDR)"]
  GON["Gönüllü (GON)"]
  FCM["FCM (sistem aktörü)"]

  subgraph SYS3["Gönüllü Koordinasyon Sistemi — İletişim ve denetim"]
    UC34(["UC-34 Gruba / kişilere duyuru gönder"])
    UC35(["UC-35 Kurum geneline duyuru gönder"])
    UC40(["UC-40 Acil alarm oluştur"])
    UC40a(["UC-40a Hedef kitle önizleme ve onay"])
    UC41(["UC-41 Alıcı durumlarını izle"])
    UC42(["UC-42 Yanıtsızlara yeniden gönder"])
    UC43(["UC-43 Alarmı iptal et / düzelt"])
    UC44(["UC-44 Alarmı kapat"])
    UC45(["UC-45 Alarmı yanıtla"])
    UC46(["UC-46 Bildirimleri görüntüle"])
    UC47(["UC-47 Denetim kayıtlarını görüntüle"])
    UC48(["UC-48 Denetim kayıtlarını filtrele"]):::ext
  end

  KOR --- UC34
  ADM --- UC35
  KOR --- UC40
  UC40 -. "include" .-> UC40a
  KOR --- UC41
  ADM -. "salt-okur" .- UC41
  KOR --- UC42
  KOR --- UC43
  KOR --- UC44
  GON --- UC45
  LDR --- UC45
  GON --- UC46
  LDR --- UC46
  ADM --- UC47
  UYM --- UC47
  ADM --- UC48
  UYM --- UC48
  UC40 --- FCM
  UC43 --- FCM

  classDef ext stroke-dasharray: 5 5
```

**Açıklama:** Acil alarmı yalnızca aktif operasyonun KOR'u gönderir (K-09, K-17); onay adımı her alarmda zorunludur (UC-40a, BR-04). Grup lideri alarm ve duyuru gönderemez (K-09). Alarmı, alıcı olarak seçilen herkes yanıtlar; gönderen kendi alarmının alıcısı olmaz. Alarm yalnızca MFA'lı web oturumundan gönderilir; Android uygulamasında alarm gönderme yoktur (ADR-013, DR-12). Otomatik eskalasyon (R2) ve gönüllünün başlattığı SOS (R3) bu diyagramda **yoktur.** Denetim kayıtlarını yalnızca ADM ve UYM görür (K-26).
