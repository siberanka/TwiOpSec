# TwiOpSec

TwiOpSec, Paper ve Folia sunucularında operatör ve yüksek ayrıcalıklı izinleri UUID tabanlı beyaz listelerle koruyan, tek JAR olarak dağıtılan bağımsız bir güvenlik eklentisidir. Modern `paper-plugin.yml` yükleme sistemi ve Paper Lifecycle/Brigadier Command API kullanılır; legacy Bukkit eklentisi değildir. T2C-OPSecurity yapılandırmalarını ilk açılışta işlemsel olarak içe aktarır; T2CodeLib çalışma zamanı bağımlılığı yoktur.

> TwiOpSec genel amaçlı bir anti-cheat, paket güvenlik duvarı veya bütün Minecraft açıklarına karşı evrensel çözüm değildir. Yetki yükseltme yüzeyini daraltır. Sunucu yazılımını, Java'yı ve diğer eklentileri güncel tutmak hâlâ zorunludur.

## Destek matrisi

| Bileşen | Durum | Doğrulanan sürüm |
|---|---|---|
| Paper | Desteklenir | Minecraft 26.2 / Paper `26.2 build 123 stable` |
| Folia | Desteklenir | Minecraft 26.2 / Folia `build 7 beta` |
| Leaf | Uyumlu smoke test | Minecraft 26.2 / Leaf `build 46 alpha` |
| Java | Gerekli | Java 25 veya daha yeni; test Java 26 ile yapıldı |
| Spigot/Bukkit | Desteklenmez | Paper scheduler API'leri kullanılır |

Folia satırındaki `beta`, TwiOpSec'in değil test edilen resmî Folia sunucu yapısının yayın kanalıdır. Daha eski veya daha yeni Minecraft sürümleri doğrulanmış sayılmaz.

## Koruma modeli

- Operatör ve korunan-izin güven listeleri UUID ile karar verir; güvenilir operatörler ayrıca korunan izinler için güvenilir kabul edilir.
- Yetkisiz OP tespit edildiğinde varsayılan olarak deop ve kick uygulanır.
- Başlangıçta sunucunun kalıcı OP kayıtları UUID beyaz listesiyle uzlaştırılır; önceden kalmış yetkisiz çevrimdışı OP kayıtları temizlenir.
- Join, command, interact, chat ve periyodik kontroller T2C ayarlarından taşınabilir.
- `/op` hedefi güven listesinde değilse engellenir; çevrimdışı hedef varsayılan olarak reddedilir.
- LuckPerms/PEX/GroupManager ayrıcalık komutları güvenilmeyen oyuncu ve command block göndericilerine kapatılır. Korunan bir düğümü güvenilmeyen kullanıcıya veya herhangi bir gruba veren pozitif komutlar konsoldan gelse bile yürütülmeden engellenir.
- LuckPerms varsa korunan pozitif kullanıcı/grup düğümleri API olayında, kullanıcı yüklenirken ve açılış/reload uzlaştırmasında kaldırılır. Normal düğüm değişiklikleri asenkron kaydedilir; transient düğümler kalıcılaştırılmaz. Depolama hatası konsol/audit uyarısı üretir. Diğer sağlayıcılarda Vault ve Bukkit attachment yolları yalnızca en iyi çaba geri dönüşüdür; Vault 1.7.3 Folia uyumlu değildir.
- Vanilla `execute`/`return`, yaygın dispatch wrapper zincirleri ve sınırlı/özyineleme güvenli `commands.yml` alias genişletmeleri içindeki komutlar da incelenir. Bilinen ve yapılandırılabilir plugin-manager kökleriyle TwiOpSec'i runtime'da unload/reload etme ve Paper/Bukkit `/reload` denemeleri engellenir.
- Paper eklenti classloader izolasyonu kullanılır; JAR içinde legacy `plugin.yml` bulunmaz.
- Sunucunun gerçek `stop` akışı engellenmez. Beklenmeyen disable sonraki açılış için kanıt işareti bırakır.
- Audit yazımı sınırlı kuyrukta ayrı bir iş parçacığında yapılır; I/O hatasında yeniden denenir, 16 MiB'de döner ve beş arşiv saklar.
- Güncelleme kontrolü ana kaynak olarak sabit HTTPS GitHub sürüm adresini kullanır. Yalnızca bu kaynak erişilemez veya doğrulanamazsa sabit GitLab yedeğine geçer; otomatik JAR indirme ya da çalıştırma yapmaz. Yeni sürüm, açılışta konsola ve oyuna her girişlerinde yalnızca aktif `trusted.operators` OP'larına doğrulanmış, tıklanabilir sürüm bağlantısıyla bildirilir.

Varsayılan korunan düğümler şunlardır ve import sırasında silinmez: `*`, `minecraft.*`, `minecraft.command.*`, `minecraft.command.op`, `bukkit.command.*`, `paper.command.*`, `essentials.*`, `luckperms.*`, `twiopsec.admin`.

`*` burada bütün izinlerle eşleşen bir glob değil, izin sistemindeki gerçek kök `*` düğümüdür. Aynı şekilde `essentials.*` yalnız gerçek wildcard izin düğümünü korur; `essentials.warps.end` gibi normal alt izinleri kapsamaz. Bir alt ağacın tamamını bilinçli olarak korumak için açık `.**` biçimi kullanılır: `example.admin.**`. Böylece yalnızca OP olduğu için varsayılan gelen veya oyunculara açıkça verilmiş zararsız izinler yanlışlıkla cezalandırılmaz.

## Citizens sunucu NPC uyumluluğu

Varsayılan kapalı olan `compatibility.citizens.server-command-npc-bypass: true` ayarı, yalnızca etkin Citizens eklentisinin sahip olduğu gerçek `NPC=true` metadata'sına sahip player NPC için tek bir doğrudan `/server <hedef>` komutunda permission-state ön kontrolünü atlar. Hedef adı 1–64 karakterlik güvenli biçimle sınırlıdır. Namespace'li `bungeecord:server` kabul edilir; `/execute`, ek argüman, iç içe slash komutu, `/op`, permission-manager ve unload/reload yolları istisna değildir.

Citizens komutunu NPC kimliğiyle çalıştırmak için örnek:

```text
/npc command add -n server survival
```

Citizens yoksa veya kapalıysa ayar fail-closed kalır ve konsola uyarı yazar. Tıklayan oyuncu olarak çalışan `-p` modu bu özel NPC kimliği istisnasına girmez; Citizens'ın Bungee `server` özel davranışı ayrıca kendi dokümantasyonunda açıklanır.

## Kurulum ve T2C importu

1. Sunucuyu durdurun ve `plugins/TwiOpSec-1.3.1.jar` dosyasını yerleştirin.
2. Eski `plugins/T2C-OPSecurity/` klasörünü ilk açılışta yerinde bırakın.
3. Sunucuyu başlatın. TwiOpSec, `config.yml`, `opWhitelist.yml` ve `permissionWhitelist.yml` dosyalarının tamamını güvenli biçimde okuyamazsa importu commit etmez. Geçerli son ayar yoksa fail-closed olarak sunucuyu durdurur.
4. Logdaki import sayılarını kontrol edin ve `plugins/TwiOpSec/config.yml` içindeki iki güven listesini gözden geçirin.
5. Eski eklentiyi ve T2CodeLib'i ancak doğrulamadan sonra kaldırın. Geri dönüş için eski JAR'ları ve klasörleri saklayın.

Import özellikleri:

- Mevcut TwiOpSec değerleri, zorunlu varsayılanlar ve eski izinler union ile birleştirilir.
- Operatör ve permission whitelist kimlikleri UUID bazında ayrı ayrı korunur.
- `check.onJoin`, `onCommand`, `onInteract`, `onChat`, timer, deop/kick, çevrimiçi OP hedefi ve güvenli özel komutlar taşınır.
- Kaynak ve hedef YAML boyutu 2 MiB ile sınırlıdır; geçersiz UTF-8, duplicate key, özel tip etiketi, alias/nesting yükü, symlink ve path traversal reddedilir.
- Önce benzersiz `plugins/TwiOpSec/migration-backups/<UTC>/` yedeği alınır. Config/marker commitinin ikinci adımı başarısız olursa önceki config ve marker geri yüklenir.
- Marker yalnızca geçerli şema, committed durumu ve SHA-256 alanları doğrulandıktan sonra importu bastırabilir. Bilinçli yeniden import: `/twiopsec import`.
- Başarılı configler `config.last-known-good.yml` olarak atomik saklanır. Hatalı reload çalışan ayarları değiştirmez; hatalı açılış configi varsa doğrulanmış son kopya kullanılabilir.
- T2CodeLib config'i yalnızca geri dönüş/arşiv amacıyla migration yedeğine eklenir; eski T2CodeLib Java API'sine binary uyumluluk sunulmaz.

Üretim verisi ve yerel sunucu yolları depoya veya JAR'a dahil edilmez. Gerçek kaynak yalnızca yerel, izole test kopyasında kullanılmıştır.

## Yönetim

```text
/twiopsec status
/twiopsec reload
/twiopsec import
/twiopsec check
```

Komutları yalnızca yerel/fiziksel sunucu konsolu çalıştırabilir; RCON, oyuncu ve command block göndericileri çalıştıramaz. Ana komutlar ve tab-complete oyun içinde sadece UUID'si `trusted.operators` listesinde bulunan ve hâlen OP olan oyunculara görünür. Bu oyuncular komutu çalıştırmayı denediğinde sunucunun varsayılan, yerelleştirilebilir “bilinmeyen komut” yanıtını alır. Diğer oyunculara komut ağacı hiç gönderilmez. Ayarlar ve örnek açıklamalar [`config.yml`](src/main/resources/config.yml) içindedir.

## Derleme

JDK 25 gerekir. Gradle Wrapper dağıtımı SHA-256 ile sabitlenmiştir.

```powershell
$env:JAVA_HOME = 'D:\path\to\jdk-25'
.\gradlew.bat clean test jar
```

Gerçek T2C dosyalarının geçici kopyasıyla import regresyon testi:

```powershell
.\gradlew.bat clean test jar -PlegacyT2Dir='D:\path\to\plugins\T2C-OPSecurity'
```

Derleme çıktısı `build/libs/TwiOpSec-1.3.1.jar` olur. Projede CI/CD tanımı bilinçli olarak yoktur; doğrulama ve yayın yerel kalite kapılarıyla yapılır.

## Sınırlar ve kaynaklar

Aynı JVM'de çalışan kötü niyetli bir eklenti güvenlik sınırının içindedir: Bukkit API'sini doğrudan çağırabilir, listener'ları kaldırabilir, TwiOpSec'i disable edebilir veya dosyalara yazabilir. Modern Paper classloader izolasyonu, genişletilmiş komut engeli ve disable kanıtı savunma katmanlarıdır; mutlak engel değildir. Eklenti allowlist'i, dosya izinleri, ayrı servis hesabı, çevrimiçi kimlik doğrulama, güncel Paper/Folia ve yalnızca güvenilir JAR'lar kullanın.

Ayrıntılar için [güvenlik modeli](docs/SECURITY_MODEL.md), [temel test raporu](docs/TEST_REPORT.md), [1.3.1 düzeltme doğrulaması](docs/TEST_REPORT_1.3.1.md), [upstream atfı](UPSTREAM.md) ve [güvenlik politikası](SECURITY.md) belgelerine bakın.

## Lisans

TwiOpSec, MIT lisansı altında bağımsız olarak yazılmıştır. T2C kaynaklarının lisans durumu ve temiz-oda yaklaşımının gerekçesi [UPSTREAM.md](UPSTREAM.md) dosyasındadır.
