import { afterEach, describe, expect, it, vi } from 'vitest';
import { CHAT_POLL_MS, chatRefetchInterval, isLiveConnected, setLiveConnected } from '../../app/liveStatus';
import { httpRealtimeService, LIVE_EVENTS } from '../../services/http';
import type { LiveEvent } from '../../services/contracts';

/** Tarayıcı EventSource'unun en küçük taklidi: adlı olay yalnızca kayıtlı dinleyiciye gider (gerçek davranış). */
class FakeEventSource {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 2;
  static last: FakeEventSource | undefined;
  readyState = FakeEventSource.CONNECTING;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private listeners = new Map<string, (e: MessageEvent<string>) => void>();
  constructor(readonly url: string) {
    FakeEventSource.last = this;
  }
  addEventListener(type: string, fn: (e: MessageEvent<string>) => void) {
    this.listeners.set(type, fn);
  }
  dispatch(type: string, data: string, id = '') {
    this.listeners.get(type)?.({ data, lastEventId: id } as MessageEvent<string>);
  }
  close() {
    this.readyState = FakeEventSource.CLOSED;
  }
}

describe('Mesajlaşma canlı akış (RAPOR_7 madde 2)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setLiveConnected(false);
  });

  it('HTTP akışı message.created/updated ve channel.updated olaylarını dinler ve iletir', () => {
    vi.stubGlobal('EventSource', FakeEventSource);
    const events: LiveEvent[] = [];
    const statuses: boolean[] = [];
    const close = httpRealtimeService('/api/v1').connect([], (e) => events.push(e), () => undefined, (s) => statuses.push(s));
    const source = FakeEventSource.last!;
    expect(LIVE_EVENTS).toEqual(expect.arrayContaining(['message.created', 'message.updated', 'channel.updated']));

    source.readyState = FakeEventSource.OPEN;
    source.onopen?.();
    source.dispatch('message.created', '{"channelId":"c1","messageId":"m1"}', '42');
    source.dispatch('channel.updated', '{"channelId":"c1"}');
    expect(events.map((e) => e.type)).toEqual(['message.created', 'channel.updated']);
    expect(events[0]).toMatchObject({ id: '42', data: { channelId: 'c1', messageId: 'm1' } });

    source.readyState = FakeEventSource.CONNECTING;   // vekil/tünel koptu, tarayıcı yeniden deniyor
    source.onerror?.();
    close();
    expect(statuses).toEqual([true, false, false]);
  });

  it('akış bağlı değilken açık sohbet kısa aralıkla yoklanır; bağlıyken yoklama yok', () => {
    expect(chatRefetchInterval(false)).toBe(CHAT_POLL_MS);
    expect(CHAT_POLL_MS).toBeLessThanOrEqual(5_000);
    expect(chatRefetchInterval(true)).toBe(false);
    setLiveConnected(true);
    expect(isLiveConnected()).toBe(true);
    setLiveConnected(false);
    expect(isLiveConnected()).toBe(false);
  });
});
