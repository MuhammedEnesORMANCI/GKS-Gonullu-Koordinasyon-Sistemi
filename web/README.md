# Web yönetim paneli

TypeScript + React + Vite, MUI, TanStack Query, React Router (ADR-008). Testler: Vitest + React Testing Library.

## Komutlar

| Komut | Açıklama |
|---|---|
| `npm install` | Bağımlılıklar (sabit sürümler) |
| `npm run dev` | Geliştirme sunucusu, `http://localhost:5173`; `/api/v1` → `http://localhost:8080` vekili |
| `npm run dev:mock` | Tüm servisler bellek içi örnek veriyle (backend gerekmez) |
| `npm run build` | `tsc -b` (strict) + üretim derlemesi |
| `npm run lint` | ESLint |
| `npm test` | `vitest run` |

## Yapı

- `src/config/services.ts` — **tek yapılandırma**: her kaynak için `http` veya `mock`.
- `src/api/` — istemci (`/api/v1`, CSRF çift gönderim, belirteç yenileme, oturum sonu, `problem+json`), hata kataloğu.
- `src/services/contracts.ts` — servis arayüzleri; `http.ts` (backend-api-design.md'ye birebir), `mock/` (bellek içi).
- `src/app/` — kabuk, oturum, sorgu anahtarları, `Idempotency-Key` niyet anahtarı.
- `src/features/` — WEB-01 (TOTP ve zorunlu parola değişikliği dahil), WEB-03, WEB-05, WEB-06 ekranları ve davetler (WEB-03/04 davet bölümü).

Kimlik yalnızca HttpOnly çerezlerle taşınır; JavaScript erişim/yenileme belirtecini okumaz ve saklamaz.
`mock` kullanan ekranlarda "Örnek veri" rozeti görünür.

## Gerçek backend ile yerel çalıştırma

1. Kök dizinde `.env` dosyası `.env.example`'a göre doldurulur (Postgres parolası, `GKS_*` anahtarları, ilk yönetici).
2. Backend: `cd backend && ./gradlew bootRun`. `.env` ortama yüklenir; Postgres ve Mailpit Docker Compose ile kendiliğinden başlar.
3. Web: `cd web && npm run dev`, ardından http://localhost:5173 açılır.
4. İlk yönetici girişi: e-posta/parola → TOTP kurulumu (gizli anahtar doğrulayıcı uygulamaya elle girilir) → 6 haneli kod → yeni parola.
5. Davet e-postaları Mailpit'te görünür: http://localhost:8025

## Örnek veri

`dev:mock` girişinde herhangi bir e-posta/parola kabul edilir. ADM/KOR rolü seçiliyse backend gibi TOTP adımı gelir;
örnek veride 6 haneli her kod kabul edilir (`000000` hatalı sayılır). "TOTP adımını atla" kutusu yalnızca tanıtım
kısayoludur. Hata durumlarını denemek için `kilitli@…` (423), `pasif@…` (403 ACCOUNT_DISABLED),
`gonullu@…` (403 CLIENT_NOT_ALLOWED), `mfa@…` (kurulu TOTP) ve parola `yanlis` (401) kullanılabilir.
