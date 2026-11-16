package org.gks.backend.realtime;

import org.gks.backend.RealtimeBus.AccessChanged;
import org.gks.backend.RealtimeBus.SessionsEnded;
import org.gks.backend.RealtimeBus.TopicEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Üretici modüllerin olayları işlem tamamlandıktan sonra akışa verilir ({@code AFTER_COMMIT}); geri alınan işlemin
 * olayı gönderilmez. İşlem dışında yayınlanan olay hemen işlenir ({@code fallbackExecution}). Bu aşamada olaylar
 * çağıran iş parçacığında gönderilir; yavaş istemci yalnızca kendi bağlantısının gönderimini bekletir.
 */
@Component
class StreamEvents {

	private final StreamHub hub;

	StreamEvents(StreamHub hub) {
		this.hub = hub;
	}

	@TransactionalEventListener(fallbackExecution = true)
	void on(TopicEvent event) {
		hub.publish(event);
	}

	@TransactionalEventListener(fallbackExecution = true)
	void on(SessionsEnded event) {
		hub.sessionsEnded(event);
	}

	@TransactionalEventListener(fallbackExecution = true)
	void on(AccessChanged event) {
		hub.accessChanged(event);
	}

}
