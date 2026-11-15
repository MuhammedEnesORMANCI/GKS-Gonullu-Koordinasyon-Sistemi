# Kullanıcı Senaryoları, Kullanıcı Hikâyeleri ve Kabul Kriterleri

> **Durum: Kesinleşti** (14.11.2026). Kabul kriterleri Given/When/Then (Verilen/Olduğunda/O zaman) biçiminde yazıldı. Önceki belirsiz parametreler onaylanan değerlerle dolduruldu (`decision-log.md` §5). Yalnızca R2 ve sonrasına ait değerler "R2 analizinde" olarak ertelendi. Rol kodları `user-roles.md`, soru ve bulgu kimlikleri `requirements-review.md` dosyasına aittir.
>
> Halka/sürüm etiketi: **Çekirdek** = dönem sonu için zorunlu · **Genişletilmiş** = zaman kalırsa (kapı KP3) · **R2/R3...** = sonraki sürüm (bkz. `mvp-scope.md` §3, §10). Bir hikâyenin bazı kriterleri farklı halkadaysa başlıkta belirtilir.
>
> **Kesinleşen çerçeve (14.11.2026, [`decision-log.md`](decision-log.md)):** "Mobil" ifadeleri **Android** uygulamasını, "web paneli" ifadeleri web uygulamasını kasteder (K-03). iOS'a özgü davranışlar iOS eklendiğinde geçerlidir. Tüm senaryolar ve testler **sentetik veriyle** yürütülür (K-04). Hikâyeler, halkaları ve kabul kriterleri **onaylanmıştır.**

---

## Bölüm A. Temel kullanıcı senaryoları (uçtan uca)

Belgedeki KS-01 – KS-04 korundu ve genişletildi. "Yeni" ile işaretlenen senaryolar belgede yok, analist tarafından eklendi.

### SC-01 Gönüllünün sisteme katılması *(yeni)*
- **Aktörler:** Kurum yöneticisi, gönüllü
- **Ön koşul:** Katılım yöntemi kararlaştırılmış (Q-02). Bu senaryo davet yöntemini varsayıyor.
- **Ana akış:**
  1. Yönetici gönüllüyü iletişim bilgisiyle davet eder.
  2. Gönüllü daveti açar, uygulamayı kurar ve hesabını doğrular.
  3. Sistem aydınlatma metnini gösterir. Gönüllü okuduğunu onaylar ve sistem metnin sürümünü ve onay zamanını kaydeder.
  4. Gönüllü zorunlu profil alanlarını doldurur.
  5. Uygulama bildirim iznini ister ve **hazırlık kontrolü** ekranında bildirim ve konum izinlerinin durumunu gösterir.
- **Alternatif akışlar:** Davet süresi dolmuştur. Gönüllü aydınlatma metnini onaylamaz, bu durumda hesap etkinleşmez (AK-10). Bildirim izni reddedilir, bu durumda kalıcı bir uyarı gösterilir.
- **Son koşul:** Aktif hesap ve kayıtlı cihaz/push token.

### SC-02 Operasyon yaşam döngüsü *(yeni, FR-19)*
- **Aktörler:** Kurum yöneticisi, koordinatör
- **Ana akış:** Operasyon "Planlandı" olarak oluşturulur, koordinatör(ler) atanır, gruplar operasyona bağlanır, operasyon "Aktif" yapılır, saha çalışması yürütülür, operasyon "Tamamlandı" yapılır.
- **Kural:** Operasyon "Aktif" değilse konum toplanmaz ve gösterilmez (BR-09). Kapanışta konum toplama durur ve kapsamlı yetkiler düşer.
- **Alternatif akış:** Operasyon "Askıda" durumuna alınır. "Askıda" durumu Genişletilmiş MVP'dedir; askıdayken konum toplanmaz ve alarm gönderilemez (BR-09, K-17).

### SC-03 Grup kurma ve toplanma noktası paylaşma *(KS-04)*
- **Aktörler:** Koordinatör (grubu kurar, üyeleri ekler, lideri atar), grup lideri (toplanma noktasını paylaşır ve günceller, K-08), gönüllüler
- **Ana akış:** Grup oluşturulur, üyeler eklenir, lider atanır, buluşma yeri, açıklama ve zaman paylaşılır, üyeler bildirim alır. Değişiklik olursa güncelleme bildirimi gönderilir.
- **Başarı:** Gönüllü hangi grup için hangi noktaya ne zaman çağrıldığını ve son güncellemeyi kimin yaptığını görür.

### SC-04 Görev atama, kabul ve tamamlama *(KS-01)*
- **Aktörler:** Koordinatör, gönüllü
- **Ana akış:** Görev oluşturulur (başlık, açıklama, öncelik, yer, zaman aralığı, hedef). Hedef kişilere bildirim gider. Gönüllü kabul veya ret eder ve yanıt zaman damgasıyla kaydedilir. Görev "Devam ediyor" ve ardından "Tamamlandı" durumuna geçer (KOR işaretler, VS-17).
- **Alternatif akışlar:** Yanıt gelmez ve atanan kişi "Yanıt bekliyor" durumunda kalır, koordinatör filtreyle görür. Görev iptal edilir ve atananlara bildirim gider. Görev kapasitesi dolar ve kalan bekleyen atamalar "Kapasite doldu" olarak kapanır (K-12).

### SC-05 Acil alarm ve yanıt takibi *(KS-02)*
- **Aktörler:** Koordinatör, hedef gönüllüler, eskalasyon alıcısı
- **Ana akış:** Koordinatör hedef kitleyi seçer ve sistem alıcı sayısını ve listesini gösterir. Koordinatör mesajı ve talep edilen eylemi girer, **onay adımı**ndan geçer ve gönderir. Mobilde alarm belirgin biçimde sunulur. Gönüllü yanıt seçeneklerinden birini seçer. Koordinatör ekranında alıcı bazlı durum (gönderildi, teslim edildi*, görüldü, yanıtlandı) görünür.
- **Yanıtsızlık:** Hatırlatmalar 2 dk aralıkla, en fazla 5 kez yapılır (K-13). Ardından alıcı "Yanıtsız" olur. Varsa eskalasyon kuralı çalışır (R2) veya koordinatör manuel olarak tekrar gönderir (MVP).
- **Hata:** Push gönderimi başarısız olursa alıcı satırında "gönderilemedi" görünür (NFR-06).
- **Sınır:** Sessiz mod, DND ve kapalı izinler aşılamayabilir (§6.1). Bu sınır arayüzde ve kullanıcı eğitiminde açıkça belirtilir.
- **Alternatif akış:** Yanlış alarm gönderilmiştir. Koordinatör alarmı iptal eder veya düzeltme gönderir (GAP-07).

### SC-06 Operasyon sırasında konum takibi *(KS-03)*
- **Aktörler:** Gönüllü, koordinatör (grup lideri konum görmez, K-15)
- **Ön koşul:** Operasyon aktif. Gönüllü operasyona dahil, aydınlatma yapılmış, izin verilmiş.
- **Ana akış:** Gönüllü paylaşımı başlatır (kullanıcı onayıyla, K-14; kural tabanlı etkinleştirme R2). Sistem konumu ve ölçüm zamanını kaydeder. Koordinatör yalnızca kendi operasyonundaki gönüllülerin son konumunu ve zaman damgasını görür. Her erişim denetim kaydına yazılır.
- **Alternatif akışlar:** İzin reddedilir, GPS alınamaz veya bağlantı yoktur. Bu durumda durum açıkça belirtilir ve eski konum canlı gibi gösterilmez.
- **Son koşul:** Operasyon kapanınca paylaşım durur ve saklama politikası uygulanır.

### SC-07 Kayıp cihaz ve hesap güvenliği *(yeni, FR-22)*
- **Aktörler:** Gönüllü, kurum yöneticisi
- **Ana akış:** Gönüllü cihazının kaybolduğunu bildirir. Yönetici kullanıcının tüm oturumlarını iptal eder ve cihazın push token'ı geçersiz kılınır. Gönüllü yeni cihazda tekrar giriş yapar.
- **Alternatif akış:** Şüpheli erişim tespit edilir. Hesap devre dışı bırakılır ve işlem denetim kaydına yazılır.

### SC-08 Bağlantı kesintisi *(yeni, FR-24 / NFR-10)*
- **Aktör:** Gönüllü
- **Ana akış:** Bağlantı kesilir. Uygulama "çevrimdışı" göstergesi ve son senkronizasyon zamanını gösterir. Konum güncellenemediği belirtilir. Bağlantı gelince veri senkronize edilir.
- **MVP sınırı:** Kontrollü ve sınırlı (K-24): durum göstergesi ve alarm yanıtının otomatik yeniden denenmesi. Genel çevrimdışı işlem kuyruğu R2.

### SC-09 Hesap kapatma ve veri silme *(yeni, §6.2)*
- **Aktörler:** Gönüllü, kurum yöneticisi (veri sorumlusu süreci)
- **Ana akış:** Gönüllü hesap kapatma talebinde bulunur. Yönetici talebi işler. Hesap erişime kapatılır, veriler politikaya göre silinir veya anonimleştirilir, denetim kaydı tutulur.
- **Bağımlılık:** Saklama süreleri belirlenmeli (AK-10). Prototipte sentetik veriyle gösterilir (K-04).

---

## Bölüm B. Kullanıcı hikâyeleri ve kabul kriterleri

### Epik E1: Hesap ve erişim

#### US-01 Gönüllü davet etme: Çekirdek
*Kurum yöneticisi olarak bir gönüllüyü davet etmek istiyorum, böylece yalnızca kurumun onayladığı kişiler sisteme katılsın.*
Kaynak: FR-01 · Bağlı: Q-02
- **AC-01.1** Verilen: yönetici oturum açmış. Olduğunda: geçerli bir iletişim bilgisiyle davet oluşturur. O zaman: davet kaydı "Bekliyor" durumunda oluşur ve gönüllüye davet iletilir.
- **AC-01.2** Verilen: davet 72 saatten eski (VS-14). Olduğunda: gönüllü daveti kullanmaya çalışır. O zaman: sistem daveti reddeder ve "süresi doldu" mesajı gösterir.
- **AC-01.3** Verilen: bir gönüllü hesabı. Olduğunda: bu hesapla davet oluşturma isteği API'ye gönderilir. O zaman: istek 403 ile reddedilir.
- **AC-01.4** Davet oluşturma denetim kaydına yazılır.
- **AC-01.5** Verilen: kurumda kayıtlı bir kullanıcı. Olduğunda: KOR onu kendi operasyonuna davet eder. O zaman: kullanıcıya uygulama içi bildirim ve e-posta gider; kullanıcı AND-03'te kabul ederse operasyon katılımcısı olur, reddederse davet kapanır (TD-01).
- **AC-01.6** Davet e-postası bir https bağlantısı ve elle girilebilir bir davet kodu içerir. Bağlantı uygulamayı açamazsa kod AND-02'ye elle girilerek aynı sonuç alınır (TD-02).

#### US-02 Hesap aktivasyonu ve aydınlatma: Genişletilmiş *(CON-15)*
*Gönüllü olarak verilerimin neden ve kimlerce işlendiğini görüp hesabımı etkinleştirmek istiyorum.*
Kaynak: FR-01, §7.2, GAP-03 · Bağlı: AK-10 · *Not: K-04 (sentetik veri) ile bu hikâyenin prototipteki gerekçesi zayıfladı. MVP'deki yeri yeniden değerlendirilmeli (CON-15, `mvp-scope.md` §9.3).*
- **AC-02.1** Verilen: geçerli davet. Olduğunda: gönüllü doğrulama adımını tamamlar. O zaman: aydınlatma metni gösterilir.
- **AC-02.2** Olduğunda: gönüllü metni onaylar. O zaman: onay zamanı ve metin sürümü kaydedilir ve hesap "Aktif" olur.
- **AC-02.3** Verilen: aydınlatma metninin yeni bir sürümü yayınlanmış. Olduğunda: gönüllü uygulamayı açar. O zaman: yeni sürüm gösterilir *(prototipte onay zorunlu değildir; metin kurgusaldır, VS-10)*.

#### US-03 Giriş, çıkış ve parola sıfırlama: Çekirdek *(AC-03.4: Genişletilmiş)*
*Kullanıcı olarak hesabıma güvenli giriş yapmak, çıkmak ve erişimimi kaybedersem kurtarmak istiyorum.*
Kaynak: FR-01, FR-22, GAP-01, GAP-12
- **AC-03.1** Doğru kimlik bilgileriyle giriş başarılı olur. Yanlış bilgilerle genel bir hata mesajı döner (hesabın var olup olmadığını açık etmez).
- **AC-03.2** 5 başarısız denemeden sonra hesap 15 dakika geçici olarak kilitlenir veya hız sınırı uygulanır.
- **AC-03.3** Çıkış yapıldığında oturum belirteci sunucu tarafında geçersizleşir. Aynı belirteçle yapılan istek 401 döner.
- **AC-03.4** *(Genişletilmiş)* Kullanıcının kendi başlattığı parola sıfırlama bağlantısı tek kullanımlıktır ve 30 dakika sonra geçersiz olur.
- **AC-03.5** *(Çekirdek)* ADM bir kullanıcının parolasını sıfırladığında kullanıcı ilk girişte yeni parola belirlemek zorundadır. İşlem denetim kaydına yazılır.

#### US-04 Profil yönetimi: Çekirdek
*Gönüllü olarak profil bilgilerimi görmek ve izin verilen alanları güncellemek istiyorum.*
Kaynak: FR-02 · Bağlı: Q-06
- **AC-04.1** Gönüllü kendi profilini görür ve düzenlenebilir alanları güncelleyebilir.
- **AC-04.2** Düzenlenemez alanlar (ör. rol) API üzerinden de değiştirilemez (istek reddedilir).
- **AC-04.3** Zorunlu alan boş bırakılırsa kayıt engellenir ve alan bazında hata gösterilir.

#### US-05 Rol ve kapsam atama: Çekirdek
*Kurum yöneticisi olarak kullanıcılara kapsamlı roller atamak istiyorum, böylece herkes yalnızca sorumlu olduğu veriye erişsin.*
Kaynak: FR-03, FR-20, NFR-02 · Bağlı: Q-03
- **AC-05.1** Yönetici bir kullanıcıya (rol, kapsam) ataması ekleyebilir veya kaldırabilir.
- **AC-05.2** Atama kaldırıldığında kullanıcının bu kapsamdaki erişimi en geç bir sonraki istekte sona erer.
- **AC-05.3** Her yetki değişikliği; işlemi yapanı, hedef kullanıcıyı, eski ve yeni değeri ve zamanı içeren bir denetim kaydı oluşturur.

#### US-06 Hesabı devre dışı bırakma ve oturum iptali: Çekirdek
*Kurum yöneticisi olarak kayıp cihaz veya şüpheli erişim durumunda kullanıcının tüm oturumlarını sonlandırmak istiyorum.*
Kaynak: FR-22, SC-07
- **AC-06.1** Olduğunda: yönetici "tüm oturumları iptal et" der. O zaman: kullanıcının tüm cihazlardaki belirteçleri geçersiz olur ve sonraki istekler 401 döner.
- **AC-06.2** Devre dışı bırakılan hesap giriş yapamaz ve bildirim almaz.
- **AC-06.3** Her iki işlem de denetim kaydına yazılır.
- **AC-06.4** Kullanıcı kendi hesabında "tüm cihazlardan çık" işlemini yapabilir.
- **AC-06.5** TEK hesap listesinde yalnızca görünen ad ve hesap durumunu görür. İletişim bilgisi, görev, konum ve alarm içeriği TEK'e döndürülmez (TD-14).

#### US-07 Yüksek yetkili hesaplarda MFA: Çekirdek *(K-19, CON-19)*
*Kurum yöneticisi ve koordinatör olarak hesabımın ikinci bir faktörle korunmasını istiyorum, çünkü konum verisine erişiyorum.*
Kaynak: NFR-03 · Bağlı: Q-17
- **AC-07.1** ADM ve KOR rollerindeki (K-19) kullanıcılar ikinci faktörü tamamlamadan web paneline erişemez.
- **AC-07.2** MFA'nın sıfırlanması yalnızca kurum yöneticisi tarafından yapılabilir ve kayda alınır.

### Epik E2: Operasyon

#### US-08 Operasyon açma ve kapatma: Çekirdek *(CON-06; "Askıda" durumu Genişletilmiş)*
*Yetkili kullanıcı olarak bir operasyonu oluşturup aktif etmek ve kapatmak istiyorum, böylece konum ve alarm gibi kritik işlevler yalnızca operasyon süresince çalışsın.*
Kaynak: FR-19, BR-09, §5.1
- **AC-08.1** Operasyon şu durumlardan birinde bulunur: Planlandı, Aktif, Askıda, Tamamlandı, İptal. Tanımsız geçişler reddedilir (ör. Tamamlandı'dan Aktif'e).
- **AC-08.2** Operasyon Aktif değilken hiçbir gönüllüden konum kabul edilmez ve hiçbir konum gösterilmez.
- **AC-08.3** Olduğunda: operasyon Tamamlandı'ya geçer. O zaman: o operasyondaki tüm konum paylaşımları 1 dk içinde durur, gönüllünün ekranında "paylaşım durdu" görünür ve operasyon kapsamlı konum erişimi kapanır. Cihaza "konumu durdur" mesajı gider; mesaj ulaşmazsa sunucu sonraki ölçümü reddeder ve servis durur (TD-18).
- **AC-08.4** Durum değişiklikleri işlemi yapanla birlikte denetim kaydına yazılır.

### Epik E3: Grup

#### US-09 Grup oluşturma ve üye yönetimi: Çekirdek
*Koordinatör olarak grup oluşturmak, üye ekleyip çıkarmak ve lider atamak istiyorum.*
Kaynak: FR-04, §8.2 · Bağlı: CON-02, Q-04, Q-07
- **AC-09.1** Koordinatör kendi operasyonunda grup oluşturabilir. Başka bir operasyonda grup oluşturma isteği 403 döner.
- **AC-09.2** Üye eklendiğinde üye bildirim alır ve grup, üyenin listesinde görünür.
- **AC-09.3** Üye çıkarıldığında üye grubun bilgilerine, görevlerine ve toplanma noktalarına erişimini kaybeder.
- **AC-09.4** Lider ataması ve üye ekleme/çıkarma denetim kaydına yazılır.
- **AC-09.5** Grup lider seçilmeden oluşturulamaz. Son lider, yerine yeni lider atanmadan çıkarılamaz veya lider rolünden alınamaz (K-32).
- **AC-09.6** Grup liderinin üye ekleme veya çıkarma isteği 403 ile reddedilir. Grup lideri kendi grubunun bilgisini ve toplanma noktasını güncelleyebilir (K-08).
- **AC-09.7** Gruba yalnızca grubun operasyonuna katılmış kullanıcılar eklenebilir (TD-01).

#### US-10 Erişim sınırı: Çekirdek
*Gönüllü olarak yalnızca üyesi olduğum grupları görmek istiyorum. Kurum olarak da üyelerin başka grupların verisini görmemesini istiyoruz.*
Kaynak: BR-01, §6.2, §8.2
- **AC-10.1** Gönüllü yalnızca üyesi olduğu grupları listeler.
- **AC-10.2** Gönüllü, üyesi olmadığı bir grubun ID'siyle doğrudan API isteği yaparsa 403 veya 404 alır ve hiçbir veri dönmez.
- **AC-10.3** Grup lideri, başka bir grubun üye, konum ve görev verilerini okuyamaz ve değiştiremez.

### Epik E4: Duyuru ve toplanma noktası

#### US-11 Duyuru gönderme: Çekirdek
*Koordinatör olarak seçili grup veya kişilere duyuru göndermek ve göndermeden önce kimin alacağını görmek istiyorum.*
Kaynak: FR-06, BR-04 · Bağlı: CON-04, Q-05
- **AC-11.1** Gönder düğmesine basmadan önce alıcı sayısı ve alıcı listesi gösterilir.
- **AC-11.2** Alıcı kapsamı göndericinin yetki kapsamıyla sınırlıdır. Kapsam dışında kalan alıcılar seçilemez ve API reddeder.
- **AC-11.3** Alıcı sayısı 50'yi aşan (VS-14) duyurularda ek bir onay adımı vardır.
- **AC-11.4** Duyuru, alıcının uygulamasında sıradan mesaj görünümünde, alarm görünümünden farklı biçimde gösterilir.
- **AC-11.5** Duyuru alıcının uygulama içi bildirim listesinde (AND-09) görünür ve okununca okundu olarak işaretlenir (TD-13).

#### US-12 Toplanma noktası paylaşma: Çekirdek *(ayrı nesne ve iptal: Genişletilmiş)*
*Koordinatör veya grup lideri olarak grubuma buluşma yeri, zaman ve açıklama paylaşmak, gerektiğinde güncellemek veya iptal etmek istiyorum.*
Kaynak: FR-09, KS-04 · Bağlı: GAP-06
- **AC-12.1** Toplanma noktası adres ve/veya harita noktası, açıklama ve zaman içerir.
- **AC-12.2** Güncelleme veya iptal yapıldığında ilgili tüm üyelere bildirim gider ve eski bilgi "güncellendi" veya "iptal edildi" olarak işaretlenir.
- **AC-12.3** Gönüllü ekranında son güncelleyen kişi ve güncelleme zamanı görünür.

### Epik E5: Görev

#### US-13 Görev oluşturma ve atama: Çekirdek
*Koordinatör olarak görev tanımlayıp kişi veya gruba atamak istiyorum.*
Kaynak: FR-07, BR-02 · Bağlı: Q-08
- **AC-13.1** Görev; başlık, açıklama, öncelik, yer/buluşma noktası, zaman aralığı ve hedef (kişi/grup) içerir. Başlık ve hedef zorunludur (diğer alanlar isteğe bağlıdır).
- **AC-13.2** Gruba atanan görev için, **yayın anında** grubun aktif üyesi olan her kişiye ayrı bir atama kaydı oluşur. Sonradan gruba katılan kişi otomatik atama almaz; KOR elle ekleyebilir *(K-12, AMB-06, TD-16)*.
- **AC-13.3** Her görev; oluşturanı, oluşturma zamanını, hedefi ve durumunu kaydeder (BR-02).
- **AC-13.4** Atanan kişiler bildirim alır.
- **AC-13.5** Atanmış bir görev düzenlendiğinde atananlara değişiklik bildirimi gider (GAP-05).
- **AC-13.6** Görevde isteğe bağlı "gereken kişi sayısı" (kapasite) tanımlanabilir. Görev detayında ve listesinde kabul sayısı / kapasite (ör. 3/5) gösterilir. Kapasite boşsa sınır yoktur (K-12).
- **AC-13.7** Kapasite, mevcut kabul sayısının altına düşürülemez.

#### US-14 Görevi kabul veya ret etme: Çekirdek
*Gönüllü olarak bana atanan görevi kabul veya ret etmek istiyorum.*
Kaynak: FR-08, BR-03
- **AC-14.1** Gönüllü atandığı görevi kabul veya ret edebilir. Yanıt, yanıt veren kullanıcı ve sunucu zaman damgasıyla kaydedilir.
- **AC-14.2** Yanıt, koordinatörün web panelinde sayfa yenilemesi gerekmeden 10 sn içinde görünür.
- **AC-14.3** Gönüllü kendisine atanmamış bir göreve yanıt veremez (API reddeder).
- **AC-14.4** Gönüllü kabul ettiği görevi bırakamaz; ret yalnızca yanıt bekleyen atamada verilebilir. Gerekirse KOR atamayı kaldırır ve gönüllü bildirim alır (K-12, VS-17).
- **AC-14.5** Verilen: görevin kapasitesi N ve N kabul var. Olduğunda: başka bir atanan kişi kabul etmeye çalışır. O zaman: istek reddedilir, atama "Kapasite doldu" durumuna geçer ve kişiye bilgi verilir. Kapasite dolduğu anda yanıt bekleyen diğer atamalar da "Kapasite doldu" olarak kapanır (K-12).
- **AC-14.6** Aynı anda gelen iki kabul isteği kapasiteyi aşmaya yol açmaz (eşzamanlılık testi).

#### US-15 Görev durumunu ilerletme: Çekirdek
*Yetkili kullanıcı olarak görevi "Devam ediyor" ve "Tamamlandı" olarak işaretlemek istiyorum.*
Kaynak: FR-08, §5.1 · Bağlı: AMB-07 → K-12, VS-17 (yalnızca KOR ilerletir)
- **AC-15.1** Yalnızca tanımlı durum geçişlerine izin verilir. Kanonik model onaylanınca (CON-05) geçiş tablosuna göre test edilir.
- **AC-15.2** Her durum değişikliği yapanı ve zamanıyla görev geçmişinde görünür.
- **AC-15.3** İptal edilen görev atananlara bildirilir ve artık kabul veya ret edilemez.

#### US-16 Yanıt bekleyen görevleri izleme: Çekirdek *(AC-16.2: Genişletilmiş)*
*Koordinatör olarak görevlerime yanıt vermemiş kişileri görmek istiyorum.*
Kaynak: KS-01 alternatif akış, FR-21 (temel)
- **AC-16.1** Görev detayında atanan kişiler yanıt durumuna göre (Bekliyor, Kabul, Ret, Kapasite doldu) ayrı listelenir.
- **AC-16.2** *(Genişletilmiş)* Görev listesi "yanıt bekleyen ataması olan görevler" filtresiyle süzülebilir.
- **AC-16.3** Yanıt süresi (varsayılan 60 dk, görev başına değiştirilebilir, VS-14) dolduğunda atama görsel olarak işaretlenir.

### Epik E6: Konum

#### US-17 Konum paylaşımını kontrol etme: Çekirdek
*Gönüllü olarak konum paylaşımımın açık olup olmadığını, en son ne zaman konum gönderildiğini görmek ve paylaşımı başlatıp durdurmak istiyorum.*
Kaynak: FR-10, BR-11 · Bağlı: Q-10
- **AC-17.1** Gönüllü ekranında paylaşım durumu (Kapalı, Aktif, Alınamıyor, Durduruldu) ve son başarılı gönderim zamanı her zaman görünür.
- **AC-17.2** Uygulamanın konum izni yoksa veya kısıtlıysa bu durum açık bir mesajla ve izin ayarlarına giden bir bağlantıyla gösterilir.
- **AC-17.3** Gönüllü paylaşımı durdurduğunda sunucu bu andan sonra konum kabul etmez ve koordinatör görünümünde gönüllü "paylaşım durduruldu" olarak işaretlenir.
- **AC-17.4** Aktif operasyon yoksa paylaşım başlatılamaz (AC-08.2).
- **AC-17.5** Paylaşım açıkken kalıcı bir bildirim görünür ve konum 60 sn'de bir gönderilir. Bildirimden paylaşım durdurulabilir (K-14, VS-18, ADR-011).
- **AC-17.6** Gönüllü birden fazla aktif operasyondaysa paylaşım operasyon başına ayrı başlatılır ve ana sayfada operasyon seçici bulunur (TD-19).
- **AC-17.7** Ağ yokken alınan konum ölçümleri biriktirilmez; bağlantı gelince yalnızca o anki ölçüm gönderilir (TD-11).
- **AC-17.8** Uygulamanın bildirimleri kapalıysa (Android 13+'da izin verilmemişse, tüm sürümlerde kullanıcı bildirimleri kapattıysa) **veya** `konum` bildirim kanalı kapalıysa konum paylaşımı başlatılamaz. Çünkü ön plan servisinin kalıcı bildirimi görünmez ve kullanıcı paylaşımın sürdüğünü fark edemez. Kullanıcı ilgili ayara yönlendirilir (LD-07, LD-17).
- **AC-17.9** Kullanıcı yalnızca yaklaşık konum izni verirse paylaşım başlatılmaz; hassas konumun neden gerektiği açıklanır ve ayarlara bağlantı gösterilir (LD-11).

#### US-18 Canlı konum görünümü: Çekirdek
*Koordinatör olarak aktif operasyonumdaki gönüllülerin son konumunu ve zamanını harita ve listede görmek istiyorum.*
Kaynak: FR-11, BR-01, BR-08, §8.2 · Bağlı: Q-11
- **AC-18.1** Koordinatör yalnızca kendi aktif operasyonunda konum paylaşan gönüllüleri görür. Her kayıtta ölçüm zamanı yer alır.
- **AC-18.2** Yetkisiz roller (gönüllü, başka operasyonun koordinatörü, teknik yönetici) aynı veriyi API'den istediğinde 403 alır.
- **AC-18.3** Konum ekranının her açılışı veya her konum sorgusu (kullanıcı, operasyon, zaman) denetim kaydına yazılır *(K-26)*.

#### US-19 Güncel olmayan konumun ayırt edilmesi: Çekirdek
*Koordinatör olarak eski konumu canlı konum sanmamak istiyorum.*
Kaynak: FR-12, BR-07, §6.2 · Bağlı: Q-10
- **AC-19.1** Ölçüm zamanı 2 dk'dan eski konum "güncel değil" etiketiyle ve canlıdan farklı bir görselle gösterilir.
- **AC-19.2** Ölçüm zamanı 15 dk'dan eskiyse "son bilinen konum (hh:mm)" olarak gösterilir.
- **AC-19.3** Güncellik, cihaz saati yerine sunucunun alış zamanı ve cihazın ölçüm zamanı birlikte değerlendirilerek hesaplanır. Aralarındaki fark 2 dk'dan büyükse kayıt işaretlenir (RT-10).
- **AC-19.4** Bağlantısı kesilen gönüllünün uygulamasında "konum gönderilemiyor" mesajı görünür (§8.2).

### Epik E7: Acil alarm

#### US-20 Acil alarm gönderme: Çekirdek
*Koordinatör olarak hedef gruba veya kişilere acil alarm göndermek ve yanlış hedefe göndermemek için onay adımından geçmek istiyorum.*
Kaynak: FR-13, BR-04 · Bağlı: Q-05, Q-14
- **AC-20.1** Alarm formu mesaj, öncelik, operasyon referansı, talep edilen eylem ve hedef içerir. Mesaj ve hedef zorunludur.
- **AC-20.2** Gönderimden önce alıcı sayısı, alıcı listesi ve "bu bir ACİL ALARMDIR" uyarısı içeren bir onay ekranı gösterilir. Onaylanmadan alarm gönderilemez.
- **AC-20.3** Sunucu, göndericinin hedef kapsamdaki yetkisini doğrular. Kapsam dışı hedef 403 döner.
- **AC-20.4** Alarm kaydı; göndereni, hedef listesinin anlık görüntüsünü ve gönderim zamanını saklar ve denetim kaydına yazılır.
- **AC-20.5** Aynı isteğin tekrarı (çift tıklama, ağ tekrarı) ikinci bir alarm oluşturmaz (idempotency, NFR-06).
- **AC-20.6** Aktif olmayan bir operasyonda alarm oluşturma isteği reddedilir (K-17).
- **AC-20.7** Hedef kitle yalnızca operasyon kapsamındaki kişi ve gruplardan seçilebilir (K-31).
- **AC-20.8** Verilen: onay penceresi açık. Olduğunda: önizlemeden sonra hedef kitle değişmiştir (ör. bir üye gruptan çıkarıldı). O zaman: gönderim reddedilir ve yeni önizleme gösterilir (TD-05).
- **AC-20.9** Android uygulaması alarm oluşturma, iptal veya kapatma ekranı içermez. Android oturumundan gelen bu tür istekler, kullanıcı KOR olsa bile 403 ile reddedilir. Alarm yalnızca MFA'lı web oturumundan gönderilir (K-19, ADR-013, DR-12).

#### US-21 Alarmın belirgin sunulması: Çekirdek
*Gönüllü olarak acil alarmı sıradan mesajlardan hemen ayırt etmek istiyorum.*
Kaynak: FR-14, NFR-07, §6.1 · Bağlı: Q-09, AMB-14
- **AC-21.1** Alarm, normal mesajlardan ayrı bir bildirim kanalı ve sesiyle gelir *(platform izin verdiği ölçüde)*.
- **AC-21.2** Uygulama ön plandayken alarm tam ekran gösterilir. Mesaj, talep edilen eylem ve yanıt düğmeleri ilk ekranda kaydırma gerekmeden görünür. Alarm metni en az 20 sp boyutundadır (VS-14).
- **AC-21.3** Yanıt düğmeleri en az 48×48 dp boyutundadır ve ekran okuyucu etiketlerine sahiptir (NFR-09).
- **AC-21.4** Test raporu, alarmın Android cihaz matrisinde (VS-05) şu durumlarda nasıl davrandığını belgeler: uygulama ön planda, arka planda, kapalı; ekran kilitli; sessiz mod; DND açık. Garanti edilemeyen davranışlar raporda açıkça belirtilir.
- **AC-21.5** Verilen: uygulama arka planda veya ekran kilitli. Olduğunda: alarm gelir. O zaman: `alarm` kanalında yüksek öncelikli, kaydırılarak kapatılamayan bir bildirim gösterilir; kullanıcı bildirime dokunduğunda alarm ekranı (AND-08) açılır (DR-01). *Android 14 ve sonrası bu bildirimi kilit ekranı dışında kaydırılabilir kılabilir; bu durumda güvence sunucu hatırlatmaları ve AND-03'teki açık alarm bandıdır (platform izin verdiği ölçüde, G-11, LD-06).*
- **AC-21.6** Uygulama kilit ekranında kendiliğinden tam ekran açmaz (full-screen intent kullanılmaz; US-42, R3). Rahatsız Etmeyin, sessiz mod veya kapatılmış bildirim izni aşılmaya çalışılmaz. Bu durumlardaki davranış AC-21.4 raporunda "garanti edilmez" olarak belirtilir (DR-01).
- **AC-21.7** Cihaz, alarmı göstermeden önce ve uygulama açılışında alarm durumunu sunucudan doğrular. İptal edilmiş veya kapanmış alarm tam ekran gösterilmez (TD-10).

#### US-22 Alarmı yanıtlama: Çekirdek
*Gönüllü olarak alarmı tek dokunuşla yanıtlamak istiyorum.*
Kaynak: FR-15 · Bağlı: Q-09, AMB-15
- **AC-22.1** Gönüllü şu seçeneklerden birini seçebilir: "Aldım", "Yardım gerekiyor", "Göreve gidiyorum" (sabit liste, K-13).
- **AC-22.2** Yanıt, sunucu zaman damgasıyla kaydedilir ve koordinatör ekranında 5 sn içinde görünür.
- **AC-22.3** "Yardım gerekiyor" yanıtı koordinatör ekranında öne çıkarılır.
- **AC-22.4** Yanıtın gönderilemediği durumda (ağ yok) kullanıcıya "yanıt iletilemedi, tekrar deneniyor" mesajı gösterilir ve yanıt iletildiğinde bu bilgi kullanıcıya gösterilir.
- **AC-22.5** Yanıtlar serbest metin içermez *(özel nitelikli veri riskini azaltmak için: RC-05, BR-12)*.
- **AC-22.6** Alarm bildirimi üç yanıt için eylem düğmesi içerir; kullanıcı uygulamayı açmadan yanıt verebilir. Teknik keşif (TV-06) başarısız olursa bu kriter kaldırılır ve yanıt yalnızca alarm ekranından verilir (TD-21).
- **AC-22.7** Alarm açıkken alıcı yanıtını değiştirebilir. Panelde son yanıt görünür, her değişiklik denetim kaydına yazılır. Alarm iptal edilmiş veya kapanmışsa yanıt reddedilir ve "Bu alarm kapatıldı / iptal edildi" gösterilir (TD-06, TD-07).

#### US-23 Yanıtsız alarm takibi ve hatırlatma: Çekirdek
*Koordinatör olarak alarma yanıt vermeyenleri görmek istiyorum. Gönüllü olarak da yanıt vermediğim alarmın hatırlatılmasını istiyorum.*
Kaynak: FR-16, BR-05, BR-10 · Bağlı: CON-07, Q-09
- **AC-23.1** Koordinatörün alarm detayında alıcılar "Yanıtladı", "Bekliyor", "Yanıtsız" ve "Gönderilemedi" olarak ayrı ayrı listelenir ve sayıları gösterilir.
- **AC-23.2** Yanıtlanmayan alarm için hatırlatma her 2 dakikada bir, en fazla 5 kez (K-13) yapılır. Ardından alıcı "Yanıtsız" durumuna geçer.
- **AC-23.3** Yanıt verildiği anda o alıcıya yeni hatırlatma gönderilmez *(BR-05'teki "yanıt sonrası tekrar" istisnası MVP'de uygulanmaz, K-13)*.
- **AC-23.4** Koordinatör yanıtsız alıcılara tek işlemle alarmı yeniden gönderebilir. Bu işlem de kayda alınır.

#### US-24 Bildirim durumlarının ayrımı: Çekirdek
*Koordinatör olarak "gönderildi" bilgisini "okudu" veya "yanıtladı" sanmamak istiyorum.*
Kaynak: FR-18, BR-06 · Bağlı: CON-09
- **AC-24.1** Her alıcı için şu durumlar ayrı alanlarda ve zaman damgasıyla gösterilir: Gönderildi (push servisine iletildi), Teslim edildi (yalnızca uygulamanın cihazda bildirimi aldığını sunucuya bildirebildiği durumlarda), Görüldü (alarm ekranı açıldı), Yanıtlandı.
- **AC-24.2** Bir durumun sinyali alınamıyorsa alan "bilinmiyor" olarak gösterilir, olumlu bir durum varsayılmaz.
- **AC-24.3** Push servisinden hata dönerse alıcı "Gönderilemedi" olarak ve hata türüyle işaretlenir (NFR-06).

#### US-25 Alarm iptali ve düzeltme: Çekirdek *(GAP-07)*
*Koordinatör olarak yanlış gönderilen bir alarmı iptal etmek veya düzeltme mesajı göndermek istiyorum.*
Kaynak: §9.1 "Yanlış hedefe acil mesaj" riski
- **AC-25.1** İptal edilen alarm için alıcılara "ALARM İPTAL EDİLDİ" bildirimi gider. Hatırlatmalar durur ve alarm ekranı iptal durumunu gösterir.
- **AC-25.2** İptal edilen alarm silinmez. Durumu "İptal" olur ve iptal eden kişi ile zamanı kaydedilir.
- **AC-25.3** KOR açık alarmı kapatabilir. Operasyon kapanınca açık alarmlar otomatik kapanır. Kapanınca hatırlatmalar durur ve cihazlara "Alarm kapatıldı" bilgisi gider (TD-06).
- **AC-25.4** "İptal et ve düzeltilmiş alarm gönder" seçeneği, iptal edilen alarmın içeriğiyle dolu yeni bir taslak açar. Yeni alarm eskisine bağlanır ve yine onay adımından geçer (TD-09).

#### US-26 Otomatik eskalasyon: R2 *(K-13)*
*Kurum olarak belirlenen sürede yanıt gelmezse durumun otomatik olarak yetkili kişiye yükseltilmesini istiyoruz.*
Kaynak: FR-17 · Bağlı: Q-09, Q-20
- **AC-26.1** Alarm gönderiminden [R2 analizinde belirlenecek süre] sonra yanıtsız alıcıların listesi [R2 analizinde belirlenecek rol]'e bildirilir (Ertelendi).
- **AC-26.2** Eskalasyon kuralının tetiklenmesi alarm geçmişinde ve denetim kaydında görünür.

### Epik E8: Denetim ve güvenlik

#### US-27 Denetim kayıtlarını görüntüleme: Çekirdek *(AC-27.4: Genişletilmiş)*
*Kurum yöneticisi veya uyum temsilcisi olarak kritik işlemlerin kaydını görmek istiyorum.*
Kaynak: FR-20, NFR-05, BR-08, §8.2 · Bağlı: Q-26
- **AC-27.1** Şu işlemler kayda alınır: yetki değişikliği, görev ataması, alarm gönderimi ve iptali, konum erişimi, oturum iptali, hesap devre dışı bırakma, operasyon durum değişikliği.
- **AC-27.2** Her kayıt; işlemi yapanı, zamanı, işlem türünü ve hedef kaynağı içerir. Parola, belirteç veya mesaj içeriği içermez.
- **AC-27.3** Gönüllü, grup lideri ve teknik yönetici iş denetim kayıtlarını okuyamaz. Hiçbir rol kayıtları uygulama üzerinden değiştiremez veya silemez.
- **AC-27.4** Kayıtlar tarih, kullanıcı ve işlem türüne göre filtrelenebilir.

#### US-28 Sunucu tarafı yetki doğrulaması: Çekirdek (teknik hikâye)
*Kurum olarak hiçbir verinin yalnızca arayüzde gizlenerek korunmamasını istiyoruz.*
Kaynak: NFR-02, §6.2
- **AC-28.1** Yetki matrisindeki (`user-roles.md` §4) her "—" hücresi için en az bir otomatik negatif API testi vardır ve bu testler geçer.
- **AC-28.2** Kaynak ID'leri tahmin edilerek yapılan isteklerde (IDOR) başka kapsamın verisi dönmez.

#### US-29 Güvenli aktarım ve sırların yönetimi: Çekirdek (teknik hikâye)
Kaynak: NFR-01, NFR-04
- **AC-29.1** HTTP istekleri reddedilir veya HTTPS'e yönlendirilir. TLS sürümü ≥ 1.2 olmalıdır.
- **AC-29.2** Kaynak kodda ve depo geçmişinde sır taraması temiz sonuç verir.
- **AC-29.3** Veritabanı ve yedekler şifrelidir *(yöntem: NFR-04 kararı)*.

#### US-30 Hesap kapatma talebi: Genişletilmiş *(CON-15)*
Kaynak: §6.2, GAP-02, SC-09 · Bağlı: AK-10 · *Not: K-04 ile prototipteki gerekçesi zayıfladı (CON-15).*
- **AC-30.1** Gönüllü uygulamadan hesap kapatma talebi oluşturabilir.
- **AC-30.2** Talep işlendiğinde hesap giriş yapamaz, bildirim almaz ve son konum kaydı silinir. Profil alanları anonimleştirilir, denetim kayıtları korunur (VS-10). İşlem kayda alınır.

#### US-31 Hazırlık kontrolü: Çekirdek: AC-31.2 · Genişletilmiş: AC-31.1, AC-31.3 *(GAP-18)*
*Gönüllü olarak alarmları kaçırmamak için telefonumun ayarlarının doğru olup olmadığını görmek istiyorum.*
- **AC-31.1** Uygulama; bildirim izni, alarm kanalı durumu, konum izni ve pil optimizasyonu (Android) durumunu tek ekranda gösterir.
- **AC-31.2** Alarm almayı engelleyen bir ayar varsa ana ekranda kalıcı bir uyarı görünür. Çekirdek uyarı kümesi: bildirim izni kapalı **veya** `alarm` kanalı kapalı/sessize alınmış. Pil optimizasyonu kontrolü Genişletilmiş'tir (LD-13).
- **AC-31.3** Koordinatör, gönüllülerinin "bildirim izni kapalı" durumunu görebilir *(Genişletilmiş; konum değil yalnızca izin durumu gösterilir, K-15 ile uyumlu)*.

#### US-32 Bağlantı durumu göstergesi: Çekirdek
Kaynak: FR-24 (kısmi), NFR-10
- **AC-32.1** Bağlantı kesildiğinde uygulama 10 sn içinde "çevrimdışı" göstergesi ve son senkronizasyon zamanını gösterir.
- **AC-32.2** Çevrimdışıyken gönderilemeyen işlemler "gönderilemedi" olarak işaretlenir ve elle tekrarlanır. İstisna: alarm yanıtı otomatik yeniden denenir (AC-22.4). Hiçbir işlem sessizce başarılı gösterilmez (K-24).

### Epik E9: Sonraki sürümler (özet)

| ID | Hikâye | Sürüm | Kaynak |
|---|---|---|---|
| US-33 | Grup sohbeti (mesaj gönderme, üyelik bitince erişim politikası) | R2 | FR-05 |
| US-34 | Gelişmiş arama ve filtreleme (gönüllü, grup, görev, alarm) | R2 | FR-21 |
| US-35 | Çevrimdışı işlem kuyruğu ve güvenli senkronizasyon | R2 | FR-24, NFR-10 |
| US-36 | Bildirim/eskalasyon politikası yapılandırma arayüzü | R2 | BR-10, GAP-17 |
| US-37 | Rapor ve dışa aktarma (kayda alınan) | R2 | GAP-09 |
| US-38 | Yetkinlik ve uygunluk etiketleri, görev eşleştirme | R2 | §7.1, GAP-20 |
| US-39 | Gönüllünün kendi başlattığı yardım çağrısı (SOS), yanlış alarm akışıyla | R3 | FR-23 |
| US-40 | SMS / sesli arama yedek kanalı | R3 | §2.2 |
| US-41 | Kurum sistemleriyle entegrasyon (kimlik, afet yönetimi) | R3 | §2.2 |
| US-42 | Android tam ekran alarm izni (iOS eklenirse Critical Alerts) ile sessiz modu aşma | R3 *(MVP'ye eklenmez, DR-01)* | §6.1, RT-03 |
| US-43 | Gönüllü olarak bir gruba katılma talebi göndermek; grup yöneticisi olarak talebi onaylamak/reddetmek | R2 | FX-02 |
| US-44 | Grup yöneticisi olarak davet bağlantısı oluşturmak ve iptal etmek | R2 | FX-02 |
| US-45 | Grup yöneticisi olarak üyelere grup içi rol (yönetici/moderatör) vermek | R2 | FX-03 |
| US-46 | Gönüllü olarak bir gruptan ayrılmak | R2 | FX-02, Q-28 |
| US-47 | Kullanıcı olarak sohbeti sessize almak; acil alarmları almaya devam etmek | R2 | FX-08 |
| US-48 | Koordinatör olarak gruba özel alarm şablonu tanımlamak | R2 | FX-09 |
| US-49 | Kullanıcı olarak topluluk grubu oluşturmak | R3 | FX-01, Q-27 |
| US-50 | Grup üyesi olarak sohbete fotoğraf veya belge eklemek | R3 | FX-05 |
| US-51 | Grup üyesi olarak grup sesli görüşmesi başlatmak veya katılmak | R4 | FX-06 |
| US-52 | Grup üyesi olarak görüntülü görüşmeye katılmak | R5 | FX-07 |

**Genişleme için MVP'de doğrulanması gereken kabul kriteri** (US-28'e ek): Grup üyelik rolü (`LEADER`) tek başına `alert.send` ve `location.view` izni vermez. Bu izinleri isteyen ve yalnızca grup rolüne sahip kullanıcının istekleri 403 ile reddedilir (`docs/architecture/authorization.md` §4, §7).

Kabul kriterleri bu hikâyeler sürüme alınırken yazılacak.

---

## Bölüm C. İzlenebilirlik özeti

| Belge gereksinimi | Hikâye(ler) |
|---|---|
| FR-01 | US-01, US-02, US-03 |
| FR-02 | US-04 |
| FR-03 | US-05, US-28 |
| FR-04 | US-09, US-10 |
| FR-05 | US-33 |
| FR-06 | US-11 |
| FR-07 | US-13 |
| FR-08 | US-14, US-15, US-16 |
| FR-09 | US-12 |
| FR-10 | US-17 |
| FR-11 | US-18 |
| FR-12 | US-19 |
| FR-13 | US-20, US-25 |
| FR-14 | US-21 |
| FR-15 | US-22 |
| FR-16 | US-23 |
| FR-17 | US-23 (manuel), US-26 |
| FR-18 | US-24 |
| FR-19 | US-08 |
| FR-20 | US-27 |
| FR-21 | US-16 (temel), US-34 |
| FR-22 | US-06 |
| FR-23 | US-39 |
| FR-24 | US-32, US-35 |
| NFR-01/04 | US-29 |
| NFR-02 | US-28 |
| NFR-03 | US-07 |
| NFR-05 | US-27 |
| NFR-06 | US-20, US-24 |
| NFR-07/09 | US-21 |
| GAP-01/02/03/07/18 | US-03, US-30, US-02, US-25, US-31 |
