import type { AlertRecipientState, AlertResponseOption, AlertState } from '../../services/types';

export const ALERT_STATE_LABEL: Record<AlertState, string> = {
  DRAFT: 'Taslak',
  SENT: 'Gönderiliyor',
  OPEN: 'Açık',
  CANCELLED: 'İptal edildi',
  CLOSED: 'Kapatıldı',
};

export const ALERT_STATE_COLOR: Record<AlertState, 'default' | 'error' | 'warning' | 'success'> = {
  DRAFT: 'default',
  SENT: 'warning',
  OPEN: 'error',
  CANCELLED: 'default',
  CLOSED: 'success',
};

export const RECIPIENT_STATE_LABEL: Record<AlertRecipientState, string> = {
  QUEUED: 'Sırada',
  SENT: 'Gönderildi',
  DELIVERED: 'Teslim edildi',
  SEEN: 'Görüldü',
  RESPONDED: 'Yanıtladı',
  UNANSWERED: 'Yanıtsız',
  FAILED: 'Gönderilemedi',
};

export const RESPONSE_LABEL: Record<AlertResponseOption, string> = {
  RECEIVED: 'Aldım',
  NEED_HELP: 'Yardım gerekiyor',
  ON_MY_WAY: 'Yoldayım',
};
