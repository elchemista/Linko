package it.linko.app.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import it.linko.app.LinkoApplication
import it.linko.app.data.Category
import it.linko.app.data.VideoLink
import it.linko.app.data.MetadataExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors

data class ServerStatus(
    val running: Boolean = false,
    val addresses: List<String> = emptyList(),
    val error: String? = null,
    val starting: Boolean = false,
    val allowEditing: Boolean = false,
    val sharedCategoryIds: Set<Long> = emptySet()
)

/** Process-local controller. Passwords are not persisted or put into Intent extras. */
object ShareServerManager {
    const val PORT = 8787
    private val mutableState = MutableStateFlow(ServerStatus())
    val state: StateFlow<ServerStatus> = mutableState.asStateFlow()
    @Volatile private var pending: Configuration? = null
    private var generation = 0L
    internal data class Configuration(val password: String, val categoryIds: Set<Long>, val generation: Long, val allowEditing: Boolean)

    @Synchronized fun start(context: Context, password: String, categoryIds: Set<Long>, allowEditing: Boolean = false) {
        if (password.length !in 8..128) {
            mutableState.value = ServerStatus(error = "Usa una password tra 8 e 128 caratteri.")
            return
        }
        if (state.value.running || state.value.starting) {
            mutableState.value = state.value.copy(error = "Ferma il server prima di cambiare password o categorie.")
            return
        }
        generation++
        pending = Configuration(password, categoryIds.toSet(), generation, allowEditing)
        mutableState.value = ServerStatus(starting = true, allowEditing = allowEditing, sharedCategoryIds = categoryIds.toSet())
        try {
            val intent = Intent(context, ShareServerService::class.java)
            context.startForegroundService(intent)
        } catch (failure: Exception) {
            pending = null
            mutableState.value = ServerStatus(error = "Impossibile avviare il server: ${failure.localizedMessage.orEmpty()}")
        }
    }

    @Synchronized fun stop(context: Context) {
        generation++
        pending = null
        // Keep the start control disabled until onDestroy has closed the old listening socket.
        mutableState.value = ServerStatus(starting = true)
        if (!context.stopService(Intent(context, ShareServerService::class.java))) mutableState.value = ServerStatus()
    }

    @Synchronized fun refreshAddresses() {
        val old = mutableState.value
        if (old.running) mutableState.value = old.copy(addresses = deviceAddresses())
    }

    @Synchronized internal fun takePending(): Configuration? = pending.also { pending = null }

    @Synchronized internal fun publish(status: ServerStatus, serviceGeneration: Long) {
        if (serviceGeneration == generation) mutableState.value = if (status.running) {
            status.copy(sharedCategoryIds = status.sharedCategoryIds + mutableState.value.sharedCategoryIds)
        } else status
    }

    @Synchronized internal fun addSharedCategory(id: Long, serviceGeneration: Long) {
        if (serviceGeneration == generation && (mutableState.value.running || mutableState.value.starting)) {
            mutableState.value = mutableState.value.copy(sharedCategoryIds = mutableState.value.sharedCategoryIds + id)
        }
    }

    @Synchronized internal fun editableScope(serviceGeneration: Long): Set<Long> {
        require(serviceGeneration == generation && mutableState.value.allowEditing &&
            (mutableState.value.running || mutableState.value.starting)) { "Modifiche dal PC non disponibili" }
        return mutableState.value.sharedCategoryIds.toSet()
    }

    @Synchronized internal fun serviceDestroyed(serviceGeneration: Long?) {
        if (serviceGeneration == generation) {
            pending = null
            mutableState.value = ServerStatus(error = mutableState.value.error)
        } else if (pending == null && mutableState.value.starting) {
            // The user stopped this generation while it was starting or running.
            mutableState.value = ServerStatus()
        }
    }

    internal fun deviceAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { when {
                it.name.startsWith("tun") -> 0
                it.name.startsWith("wlan") || it.name.startsWith("ap") -> 1
                else -> 2
            } }
            .flatMap { network -> network.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }
            .map { "http://${it.hostAddress}:$PORT" }.distinct()
    }.getOrDefault(emptyList())
}

class ShareServerService : Service() {
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "linko-server-start").apply { isDaemon = true } }
    @Volatile private var server: LibraryHttpServer? = null
    @Volatile private var destroyed = false
    private var startRequested = false
    private var foregroundFailed = false
    private var serviceGeneration: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Raccolta sul PC", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Mostra quando la raccolta è accessibile dal browser."
        })
        val stop = PendingIntent.getService(this, 3, Intent(this, ShareServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 4, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Linko · Raccolta sul PC")
            .setContentText("Server attivo con password. Tocca per vedere l’indirizzo.")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Ferma", stop).build())
            .apply { launch?.let(::setContentIntent) }
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (failure: Exception) {
            foregroundFailed = true
            ShareServerManager.takePending()?.let { config ->
                serviceGeneration = config.generation
                ShareServerManager.publish(ServerStatus(error = "Android non ha permesso l’avvio del server: ${failure.localizedMessage.orEmpty()}"), config.generation)
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (foregroundFailed) { stopSelf(); return START_NOT_STICKY }
        if (destroyed || startRequested) return START_NOT_STICKY
        val config = ShareServerManager.takePending()
        if (config == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        serviceGeneration = config.generation
        startRequested = true
        worker.execute {
            try {
                val repository = (application as LinkoApplication).repository
                val writer = if (config.allowEditing) object : LibraryWriter {
                    override fun saveLinks(links: List<VideoLink>): Int = synchronized(repository) {
                        val scope = ShareServerManager.editableScope(config.generation)
                        val current = repository.snapshot()
                        val normalized = links.map { link -> link.copy(url = MetadataExtractor.extractUrl(link.url) ?: throw IllegalArgumentException("Indirizzo non valido")) }
                        normalized.forEach { link ->
                            require(link.categoryId in scope) { "Categoria non disponibile" }
                            if (link.id > 0) require(current.links.any { it.id == link.id && it.categoryId in scope }) { "Link non disponibile" }
                            require(current.links.none { it.url == link.url && it.categoryId !in scope }) { "Link non disponibile" }
                        }
                        repository.saveLinks(normalized)
                    }
                    override fun deleteLink(id: Long) = synchronized(repository) {
                        val scope = ShareServerManager.editableScope(config.generation)
                        require(repository.snapshot().links.any { it.id == id && it.categoryId in scope }) { "Link non disponibile" }
                        repository.deleteLink(id)
                    }
                    override fun saveCategory(category: Category): Long {
                        val id = synchronized(repository) {
                            val scope = ShareServerManager.editableScope(config.generation)
                            if (category.id > 0) require(category.id in scope) { "Categoria non disponibile" }
                            if (category.id == 0L) {
                                val proposed = category.name.trim().replace(Regex("\\s+"), " ")
                                require(repository.snapshot().categories.none { it.name.equals(proposed, true) }) { "Nome già in uso" }
                            }
                            repository.saveCategory(category.name, category.icon, category.color, category.id, category.matchKeywords)
                        }
                        if (category.id == 0L) ShareServerManager.addSharedCategory(id, config.generation)
                        return id
                    }
                } else null
                val created = LibraryHttpServer(config.password, config.categoryIds, repository::snapshot,
                    port = ShareServerManager.PORT, bindAddress = InetAddress.getByName("0.0.0.0"),
                    allowEditing = config.allowEditing, writer = writer)
                synchronized(this) {
                    if (destroyed) { created.close(); return@execute }
                    try { created.start() } catch (failure: Exception) { created.close(); throw failure }
                    server = created
                    ShareServerManager.publish(ServerStatus(running = true, addresses = ShareServerManager.deviceAddresses(),
                        allowEditing = config.allowEditing, sharedCategoryIds = config.categoryIds), config.generation)
                }
            } catch (failure: Exception) {
                if (!destroyed) {
                    ShareServerManager.publish(ServerStatus(error = "Impossibile aprire la porta ${ShareServerManager.PORT}: ${failure.localizedMessage.orEmpty()}"), config.generation)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(this) {
            destroyed = true
            server?.close()
            server = null
        }
        worker.shutdownNow()
        ShareServerManager.serviceDestroyed(serviceGeneration)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "linko_pc_server"
        private const val NOTIFICATION_ID = 8787
        private const val ACTION_STOP = "it.linko.app.STOP_SERVER"
    }
}
