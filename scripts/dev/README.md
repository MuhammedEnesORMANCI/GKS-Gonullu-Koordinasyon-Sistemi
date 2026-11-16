# Geliştirme araçları

## Demo verisi (`demo-seed.ps1`, Görev 25)

Geliştirme veritabanına az ve gerçekçi, **kurgusal** Türkçe demo verisi **ekler**. Var olan hiçbir kayıt silinmez veya
değiştirilmez. Bu bir Flyway migration'ı değildir.

**Eklenenler:** "Marmara Deprem Tatbikatı 2026" (Aktif, Kocaeli İzmit, 3 km alan) ve "Ege Orman Yangını Müdahale
Tatbikatı" (Planlandı, İzmir Seferihisar, 5 km alan); her birinde 2 grup ve koordinatlı toplanma noktası; 8 demo gönüllü
hesabı (`@demo.local`, ortak parola); farklı durumlarda 4 görev ve atamalar; 2 operasyon duyurusu (yalnızca gelen
kutusu); 4 mesaj kanalında 10 mesaj; 6 gönüllünün son konumu; 2 yayında kamu duyurusu; tek bir `DEMO_DATA_SEEDED`
denetim kaydı.

**Mevcut hesaplar:** bilgileri değişmez; yalnızca yeni operasyonlara rol eklenir. Kurum ADM'leri iki operasyonda KOR,
herhangi bir operasyonda KOR olanlar aktif operasyonda KOR, gönüllüler (GON) aktif operasyonda katılımcı olur
(ve "Arama Kurtarma Ekibi 1" grubuna eklenir).

**Eklenmeyenler:** açık acil alarm, telefon bildirimi (FCM) ya da e-posta; araç çalışırken bildirim teslim işçisi ve
diğer zamanlanmış işler kapalıdır.

### Çalıştırma

Backend kapalıyken, kök dizinden (PowerShell):

```powershell
$env:GKS_DEMO_PASSWORD = '<en az 12 karakter>'   # yalnızca bu oturumda; depoya yazılmaz
.\scripts\dev\demo-seed.ps1
```

Araç bekleyen migration'ları uygular, veriyi ekler, `DEMO VERİSİ: … eklendi` satırını yazar ve kendiliğinden
kapanır. Yeniden çalıştırmak güvenlidir: ikinci çalıştırma `0 yeni satır` yazar.

### Üretimde neden çalışmaz

`demo-seed` profili, veritabanı adresinin bu makine (`localhost`/`127.0.0.1`) olması ve `GKS_DEMO_CONFIRM_LOCAL=true`
zorunludur; `prod`/`production`/`staging` profilleri reddedilir (`DemoSeedGuard`). Koşul sağlanmazsa hiçbir şey
eklenmeden çıkış kodu 1 ile kapanır.

### Geri alma

Demo öncesi yedekten geri yükleyin (bkz. rapor): `pg_restore --clean --if-exists`. Demo kayıtları sabit kimlikli
olduğundan elle de ayıklanabilir, ancak önerilen yol yedektir.
