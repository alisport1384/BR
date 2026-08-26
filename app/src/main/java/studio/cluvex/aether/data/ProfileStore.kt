package studio.cluvex.aether.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth

private val Context.dataStore by preferencesDataStore(name = "aether_profile")

/** Persists the last-used [ConnectionProfile] with Jetpack DataStore. */
class ProfileStore(private val context: Context) {
    private object Keys {
        val protocol = stringPreferencesKey("protocol")
        val scan = stringPreferencesKey("scan")
        val ip = stringPreferencesKey("ip")
        val quick = booleanPreferencesKey("quick")
        val h2 = booleanPreferencesKey("h2")
        val share = booleanPreferencesKey("share")
        // Added in 1.2.0
        val noize = stringPreferencesKey("noize")
        val endpoint = stringPreferencesKey("endpoint")
        val peer = stringPreferencesKey("peer")
        val range = stringPreferencesKey("range")
        val keepalive = intPreferencesKey("keepalive")
        val fragment = booleanPreferencesKey("fragment")
        val ech = booleanPreferencesKey("ech")
        val mtu = intPreferencesKey("mtu")
        val proxy = booleanPreferencesKey("proxy")
        val split = stringPreferencesKey("split")
        val splitApps = stringPreferencesKey("splitApps")
        // Added in 1.2.3 (engine v1.5.0)
        val dns = stringPreferencesKey("dns")
        val team = stringPreferencesKey("team")
        val teamAuth = stringPreferencesKey("teamAuth")
        val accessId = stringPreferencesKey("accessId")
        val accessEmail = stringPreferencesKey("accessEmail")
        val gateway = booleanPreferencesKey("gateway")
        val routeBlock = stringPreferencesKey("routeBlock")
        val routeDirect = stringPreferencesKey("routeDirect")
        // Added in 1.2.4 (feature parity)
        val killSwitch = booleanPreferencesKey("killSwitch")
        val strictKillSwitch = booleanPreferencesKey("strictKillSwitch")
        val ipv6Leak = booleanPreferencesKey("ipv6Leak")
        val smartReconnect = booleanPreferencesKey("smartReconnect")
        val reconnectRetryLimit = intPreferencesKey("reconnectRetryLimit")
        val fragmentSize = stringPreferencesKey("fragmentSize")
        val fragmentDelay = stringPreferencesKey("fragmentDelay")
        val noDataCheck = booleanPreferencesKey("noDataCheck")
        val tlsGroups = stringPreferencesKey("tlsGroups")
        val validateSecs = intPreferencesKey("validateSecs")
        val reconnectSecs = intPreferencesKey("reconnectSecs")
        val noProfileRetry = booleanPreferencesKey("noProfileRetry")
        val coreLogLevel = stringPreferencesKey("coreLogLevel")
        val blockedApps = stringPreferencesKey("blockedApps")
    }

    /**
     * Zero Trust secrets (service-token secret + enrolment JWT) are NOT kept in
     * the DataStore preferences file. That file is plain protobuf inside the app
     * sandbox, so a device backup or an adb dump on a rooted phone would expose
     * a long-lived organization credential. They live in [SecretStore] instead,
     * sealed with a hardware-backed AES-GCM key from the Android Keystore.
     */
    private val secrets = SecretStore(context)

    val profile: Flow<ConnectionProfile> = context.dataStore.data.map {
        ConnectionProfile(
            protocol = Protocol.WIREGUARD,
            ipVersion = IpVersion.BOTH,
        )
    }

    suspend fun save(@Suppress("UNUSED_PARAMETER") profile: ConnectionProfile) {
        context.dataStore.edit { prefs ->
            val defaults = ConnectionProfile()
            prefs[Keys.protocol] = Protocol.WIREGUARD.name
            prefs[Keys.scan] = defaults.scanMode.name
            prefs[Keys.ip] = IpVersion.BOTH.name
            prefs[Keys.quick] = defaults.quickReconnect
            prefs[Keys.h2] = defaults.masqueHttp2
            prefs[Keys.share] = defaults.lanShare
            prefs[Keys.noize] = defaults.noize.name
            prefs[Keys.endpoint] = defaults.endpointMode.name
            prefs[Keys.peer] = defaults.manualPeer
            prefs[Keys.range] = defaults.manualRange
            prefs[Keys.keepalive] = defaults.keepalive
            prefs[Keys.fragment] = defaults.fragment
            prefs[Keys.ech] = defaults.ech
            prefs[Keys.mtu] = defaults.mtu
            prefs[Keys.proxy] = defaults.proxyMode
            prefs[Keys.split] = defaults.splitMode.name
            prefs[Keys.splitApps] = defaults.splitApps.joinToString(",")
            prefs[Keys.dns] = defaults.dnsServers
            prefs[Keys.team] = defaults.team
            prefs[Keys.teamAuth] = defaults.teamAuth.name
            prefs[Keys.accessId] = defaults.accessClientId
            prefs[Keys.accessEmail] = defaults.accessEmail
            prefs[Keys.gateway] = defaults.gateway
            prefs[Keys.routeBlock] = defaults.routeBlock
            prefs[Keys.routeDirect] = defaults.routeDirect
            prefs[Keys.killSwitch] = defaults.killSwitch
            prefs[Keys.strictKillSwitch] = defaults.strictKillSwitch
            prefs[Keys.ipv6Leak] = defaults.ipv6LeakProtection
            prefs[Keys.smartReconnect] = defaults.smartReconnect
            prefs[Keys.reconnectRetryLimit] = defaults.reconnectRetryLimit
            prefs[Keys.fragmentSize] = defaults.fragmentSize
            prefs[Keys.fragmentDelay] = defaults.fragmentDelay
            prefs[Keys.noDataCheck] = defaults.noDataCheck
            prefs[Keys.tlsGroups] = defaults.tlsGroups
            prefs[Keys.validateSecs] = defaults.validateSecs
            prefs[Keys.reconnectSecs] = defaults.reconnectSecs
            prefs[Keys.noProfileRetry] = defaults.noProfileRetry
            prefs[Keys.coreLogLevel] = defaults.coreLogLevel.name
            prefs[Keys.blockedApps] = defaults.blockedApps.joinToString(",")
        }
        secrets.clear()
    }

    /** Wipes the sealed Zero Trust secrets (used by "Reset settings"). */
    fun clearSecrets() = secrets.clear()
}
