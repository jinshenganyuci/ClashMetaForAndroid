package com.github.kr328.clash.xboard

import android.content.Context
import com.github.kr328.clash.R
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.model.XboardProfile
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.UUID

class XboardProfileUnavailableException : IOException("Account subscription is unavailable")

class XboardProfiles(context: Context) {
    private val name = context.getString(R.string.xb_site)

    suspend fun sync(source: String, activate: Boolean = true): UUID {
        val available = try {
            withProfile { syncXboard(source, name, activate) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Account subscription update failed")
        }
        if (!available) throw XboardProfileUnavailableException()
        return XboardProfile.id
    }

    suspend fun current(): Profile? = withProfile {
        queryByUUID(XboardProfile.id)?.takeIf { it.imported && !it.pending }
    }

    suspend fun remove() {
        withProfile { delete(XboardProfile.id) }
    }
}
