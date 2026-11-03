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
            sync(bind(email, bearer), force = true, activate = true)
        }
    }

    suspend fun register(email: String, password: String, code: String, invite: String): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val bearer = XboardApi.register(email, password, code, invite)
                // The server has created the account. Never repeat the POST if saving its session fails.
                try {
                    bind(email, bearer)
                    true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }
        }

    private suspend fun bind(email: String, bearer: String): XboardBinding {
        val old = store.read()
        val binding = if (old != null && old.email.equals(email.trim(), ignoreCase = true)) {
            old.copy(bearer = bearer)
        } else {
            profiles.remove()
            XboardBinding(email.trim(), bearer)
        }
        store.save(binding)
        return binding
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

    suspend fun clearExpiredSession(bearer: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (store.read()?.bearer == bearer) clear()
        }
    }

    private suspend fun clear() {
        profiles.remove()
        store.clear()
        checkedEmail = null
    }

    // A panel may rotate subscription hosts or tokens independently of the imported profile.
    suspend fun latestMembership(): XboardBinding? = withContext(Dispatchers.IO) {
        lock.withLock {
            val binding = store.read() ?: return@withLock null
            binding.copy(subscription = fetchSubscription(binding))
        }
    }

    private suspend fun fetchSubscription(binding: XboardBinding): XboardSubscription =
        try {
            XboardApi.getSubscribe(binding.bearer)
        } catch (e: XboardSessionExpiredException) {
            clear()
            throw e
        }

    private suspend fun sync(binding: XboardBinding, force: Boolean, activate: Boolean): XboardBinding {
        val subscription = fetchSubscription(binding)
        val now = System.currentTimeMillis()
        var updated = binding.copy(subscription = subscription)
        if (subscription.planId == null || subscription.isExpired(now)) {
            profiles.remove()
            updated = updated.copy(profileId = null, lastSync = null)
        } else {
            val profile = profiles.current()
            val previous = binding.subscription
            val due = profile == null || (profile.interval > 0 && now - profile.updatedAt >= profile.interval)
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
