#!/usr/bin/env node
// Gösterim yardımcısı (yalnızca yerel geliştirme): Android uygulaması henüz olmadığından, Mailpit'e düşen
// "yeni kullanıcı" davet e-postalarındaki kodu mevcut ID-16 ucuyla (POST /api/v1/invitations/accept) kabul eder.
// Sonuç gerçektir: veritabanında gönüllü hesabı ve (operasyon davetinde) katılım oluşur. Yeni endpoint yoktur.
//
// Kullanım:  node scripts/demo-davet-kabul.mjs [gönüllü-parolası]
// Ortam:     GKS_API (varsayılan http://localhost:8080), MAILPIT (varsayılan http://localhost:8025)

import { randomUUID } from 'node:crypto';

const API = process.env.GKS_API ?? 'http://localhost:8080';
const MAILPIT = process.env.MAILPIT ?? 'http://localhost:8025';
const PASSWORD = process.argv[2] ?? 'Gonullu-Demo-2026';
const SUBJECT = 'Gönüllü Koordinasyon Sistemi daveti';

function namesFrom(email) {
  const words = email.split('@')[0].split(/[._-]+/).filter(Boolean);
  const cap = (w) => w.charAt(0).toLocaleUpperCase('tr-TR') + w.slice(1);
  const fullName = words.map(cap).join(' ') || 'Gönüllü';
  const displayName = words.length > 1 ? `${cap(words[0])} ${cap(words.at(-1)).charAt(0)}.` : fullName;
  return { fullName: fullName.padEnd(2, '.').slice(0, 100), displayName: displayName.padEnd(2, '.').slice(0, 40) };
}

async function json(url, init) {
  const response = await fetch(url, init);
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : undefined };
}

const list = await json(`${MAILPIT}/api/v1/messages?limit=200`).catch(() => null);
if (!list || list.status !== 200) {
  console.error(`Mailpit'e ulaşılamadı (${MAILPIT}). Backend 'bootRun' ile çalışıyor mu?`);
  process.exit(1);
}

const invites = list.body.messages.filter((m) => m.Subject === SUBJECT);
if (invites.length === 0) {
  console.log('Kabul edilecek yeni kullanıcı daveti yok. Web panelinden kayıtlı olmayan bir e-postaya davet gönderin.');
  process.exit(0);
}

let accepted = 0;
for (const m of invites) {
  const email = m.To?.[0]?.Address ?? '';
  const message = await json(`${MAILPIT}/api/v1/message/${m.ID}`);
  const code = /#([A-Z0-9]{8})\b/.exec(message.body?.Text ?? '')?.[1];
  if (!code) {
    console.log(`- ${email}: e-postada kod bulunamadı, atlandı`);
    continue;
  }
  const { fullName, displayName } = namesFrom(email);
  const result = await json(`${API}/api/v1/invitations/accept`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
    body: JSON.stringify({ code, password: PASSWORD, fullName, displayName, installationId: `demo-${randomUUID()}` }),
  });
  if (result.status === 201) {
    accepted += 1;
    console.log(`+ ${email}: hesap açıldı (${fullName}); davet kabul edildi`);
  } else if (result.status === 410) {
    console.log(`- ${email}: davet artık geçerli değil (${result.body?.invitationStatus ?? 'kullanılmış'}), atlandı`);
  } else {
    const fields = (result.body?.errors ?? []).map((e) => `${e.field}:${e.code}`).join(', ');
    console.log(`! ${email}: ${result.status} ${result.body?.code ?? ''} ${fields}`);
  }
}
console.log(`\n${accepted} davet kabul edildi. Gönüllü parolası: ${PASSWORD} (gönüllüler web paneline giremez; LD-03).`);
