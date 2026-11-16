package org.gks.backend.notifications;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Testler: etkin, ağa çıkmayan push geçidi (diğer modüllerin testleri {@code @Primary} bean olarak kullanır). */
public class PushGatewayForTests implements PushGateway {

	public final List<String> sentTypes = new CopyOnWriteArrayList<>();

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public Result send(Message message) {
		sentTypes.add(message.type());
		return Result.sent("projects/test/messages/1");
	}

}
