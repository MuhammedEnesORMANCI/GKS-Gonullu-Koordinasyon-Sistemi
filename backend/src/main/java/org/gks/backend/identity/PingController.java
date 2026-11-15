package org.gks.backend.identity;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hafif erişilebilirlik ucu (ID-34). İstemcilerin bağlantı göstergesi (AC-32.1) bunu kullanır.
 * Kimlik gerektirmez; gövde ve sürüm bilgisi döndürmez.
 */
@RestController
@RequestMapping("/api/v1")
class PingController {

	@GetMapping("/ping")
	ResponseEntity<Void> ping() {
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

}
