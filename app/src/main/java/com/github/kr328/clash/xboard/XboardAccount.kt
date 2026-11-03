package com.github.kr328.clash.xboard

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class XboardAccount(context: Context) {
    private val store = XboardStore(context.applicationContext)
    private val profiles = XboardProfiles(context.applicationContext)

    suspend fun cached(): XboardBinding? = withContext(Dispatchers.IO) {
        lock.withLock { store.read() }
    }

    suspend fun login(email: String, password: String): XboardBinding = withContext(Dispatchers.IO) {
        lock.withLock {
            val bearer = XboardApi.login(email.trim(), password)
            val old = store.read()
            if (old == null || !old.email.equals(email.trim(), ignoreCase = true)) {
                profiles.remove()
            }
            val binding = if (old != null && old.email.equals(email.trim(), ignoreCase = true)) {
                old.copy(bearer = bearer)
            } else {
                XboardBinding(email.trim(), bearer)
            }
            store.save(binding)
            sync(binding, force = true, activate = true)
        }
    }

    suspend fun refresh(force: Boolean): XboardBinding? = withContext(Dispatchers.IO) {
        lock.withLock {
            val binding = store.read() ?: return@withLock null
            if (!force && checkedEmail == binding.email &&
                SystemClock.elapsedRealtime() - lastCheck < TimeUnit.MINUTES.toMillis(5)) {
                return@withLock binding
            }
            sync(binding, force, activate = false)
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        lock.withLock { clear() }
    }

    private suspend fun clear() {
        profiles.remove()
        store.clear()
        checkedEmail = null
    }

    private suspend fun sync(binding: XboardBinding, force: Boolean, activate: Boolean): XboardBinding {
        val subscription = try {
            XboardApi.getSubscribe(binding.bearer)
        } catch (e: XboardSessionExpiredException) {
            clear()
            throw e
        }
        val now = System.currentTimeMillis()
        var updated = binding.copy(subscription = subscription)
        if (subscription.planId == null || subscription.isExpired(now)) {
            profiles.remove()
            updated = updated.copy(profileId = null, lastSync = null)
        } else {
            val profile = profiles.current()
            val previous = binding.subscription
            val due = profile == null || now - profile.updatedAt >=
                (profile.interval.takeIf { it > 0 } ?: TimeUnit.HOURS.toMillis(24))
            val changed = previous?.planId != subscription.planId ||
                previous?.expiredAt != subscription.expiredAt || profile?.source != subscription.subscribeUrl
            if (force || changed || due) {
                try {
                    val uuid = profiles.sync(subscription.subscribeUrl, activate || profile == null)
                    updated = updated.copy(profileId = uuid, lastSync = now)
                } catch (e: XboardProfileUnavailableException) {
                    profiles.remove()
                    updated = updated.copy(profileId = null, lastSync = null)
                }
            } else {
                updated = updated.copy(profileId = profile?.uuid, lastSync = profile?.updatedAt)
            }
        }
        store.save(updated)
        checkedEmail = binding.email
        lastCheck = SystemClock.elapsedRealtime()
        return updated
    }

    companion object {
        private val lock = Mutex()
        private var checkedEmail: String? = null
        private var lastCheck = 0L
    }
}

internal fun XboardSubscription.isExpired(now: Long = System.currentTimeMillis()): Boolean =
    expiredAt?.let { it <= now / 1000 } == true
