/**
 * Oturum olayları (web-screen-api-mapping.md §1.4). API istemcisi olayı yayar; uygulama kabuğu
 * (SessionProvider) önbelleği temizler ve WEB-01'e yönlendirir. Diğer sekmelere BroadcastChannel ile duyurulur.
 */

export type SessionEndReason =
  | 'AUTH_SESSION_REVOKED'
  | 'AUTH_UNAUTHENTICATED'
  | 'ACCOUNT_DISABLED'
  | 'REFRESH_FAILED'
  | 'LOGOUT';

type Listener<T> = (value: T) => void;

class Emitter<T> {
  private listeners = new Set<Listener<T>>();
  on(listener: Listener<T>): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }
  emit(value: T): void {
    for (const l of [...this.listeners]) l(value);
  }
}

const CHANNEL_NAME = 'gks-session';

export class SessionEvents {
  readonly sessionEnded = new Emitter<SessionEndReason>();
  /** 403 FORBIDDEN: `me/context` yenilenmeli (mapping §0.1 madde 1). */
  readonly forbidden = new Emitter<void>();
  private channel: BroadcastChannel | null = null;

  constructor() {
    if (typeof BroadcastChannel !== 'undefined') {
      try {
        this.channel = new BroadcastChannel(CHANNEL_NAME);
        this.channel.onmessage = (event: MessageEvent<{ type: string; reason: SessionEndReason }>) => {
          if (event.data?.type === 'session-ended') this.sessionEnded.emit(event.data.reason);
        };
      } catch {
        this.channel = null;
      }
    }
  }

  /** Bu sekmede oturum sona erdi: dinleyicilere ve diğer sekmelere bildir. */
  endSession(reason: SessionEndReason): void {
    this.sessionEnded.emit(reason);
    try {
      this.channel?.postMessage({ type: 'session-ended', reason });
    } catch {
      // Kanal kapalıysa yalnızca bu sekme temizlenir.
    }
  }
}

export const sessionEvents = new SessionEvents();
