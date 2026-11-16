package org.gks.android.data

import org.gks.android.data.remote.GksApi
import org.gks.android.data.remote.Page
import org.gks.android.data.remote.PublicAnnouncement

/** ADR-023: kamu duyuruları (yalnızca yayındakiler); iç (operasyon) duyuruları gelen kutusundadır. */
class PublicAnnouncementRepository(private val api: GksApi, private val caller: ApiCaller) {

	suspend fun page(cursor: String? = null, limit: Int = PAGE): ApiResult<Page<PublicAnnouncement>> =
		caller.call { api.publicAnnouncements(limit, cursor) }

	companion object {
		const val PAGE = 20
	}
}
