package org.gks.backend.alerts;

import java.util.List;

/** K-13: sabit üç seçenek; serbest metin yok (AC-22.5). */
enum ResponseOption {
	RECEIVED, NEED_HELP, ON_MY_WAY;

	static final List<String> ALL = List.of(RECEIVED.name(), NEED_HELP.name(), ON_MY_WAY.name());
}
