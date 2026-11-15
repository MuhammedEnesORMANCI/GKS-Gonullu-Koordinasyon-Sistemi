# Diyagramlar

Tüm diyagramlar Mermaid ile yazıldı ve `mermaid-cli` ile derlenerek sözdizimi doğrulandı. Her dosyada diyagramın altında kısa bir açıklama bulunur.

## Gösterim kuralları

| Gösterim | Anlamı |
|---|---|
| Düz çizgi / çerçeve | Çekirdek MVP |
| **Kesik çerçeve** | Genişletilmiş MVP (zaman kalırsa, kapı KP3) |
| `TD-xx` | Kabul edilmiş tasarım kararı (`../design/system-architecture.md` §9) |
| R2+ | Yalnızca ERD'de bağlanma noktası olarak ayrı bir diyagramda; diğer diyagramlarda gösterilmez |
| Uç nokta adları | Taslaktır; OpenAPI sözleşmesi Hafta 3'te yazılacak (ADR-010) |

## Dizin

| # | Tür | Dosya | Kapsam |
|---|---|---|---|
| 1 | Use Case | `use-case-diagram.md` | 6 rol, 3 görünüm: hesap ve erişim · operasyon ve saha · iletişim ve denetim |
| 2 | Activity | `activity-invitation.md` | Davetle sisteme katılma |
| 2 | Activity | `activity-join-operation.md` | Operasyona katılma (TD-01) |
| 2 | Activity | `activity-task.md` | Görev atama, kapasite, durum güncelleme |
| 2 | Activity | `activity-location.md` | Konum paylaşımını başlatma ve durdurma |
| 2 | Activity | `activity-alert.md` | Alarm oluşturma, gönderme, yanıtlama, hatırlatma, iptal |
| 3 | Sequence | `sequence-login.md` | Web (TOTP) ve Android girişi, yetki denetimi, belirteç yenileme |
| 3 | Sequence | `sequence-invitation.md` | Davet süreci |
| 3 | Sequence | `sequence-task-assignment.md` | Görev atama ve eşzamanlı kabul |
| 3 | Sequence | `sequence-location.md` | Konum paylaşımı ve operasyon kapanışında durdurma |
| 3 | Sequence | `sequence-alert.md` | Acil alarm ve bildirim süreci |
| 4 | Component | `component-diagram.md` | Android, Web, Backend modülleri, harici servisler |
| 5 | Deployment | `deployment-diagram.md` | Docker Compose ana makinesi ve harici servisler |
| 6 | ERD | `erd.md` | Çekirdek MVP, Genişletilmiş MVP, R2+ bağlanma noktaları |

Durum diyagramları (alarm ve alıcı) `../design/emergency-alert-design.md` §1'de, üyelik durum makinesi `../domain-model.md` §3.3'tedir.
