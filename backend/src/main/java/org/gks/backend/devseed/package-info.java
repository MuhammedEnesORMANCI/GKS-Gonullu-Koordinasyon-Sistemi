/**
 * Geliştirme aracı: demo verisi ekleme (yalnızca {@code demo-seed} profili; Flyway migration değildir). Üretimde
 * çalışmaz: profil, yerel veritabanı adresi ve açık onay özelliği zorunludur. Bkz. {@code scripts/dev/README.md}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Demo verisi (geliştirme)")
package org.gks.backend.devseed;
