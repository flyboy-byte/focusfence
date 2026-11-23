package app.focusfence.vpn

import android.content.Context

/**
 * Whether the owner wants the filter running. Set when the tunnel is established, cleared only by an accepted
 * stop from the owner. An app update or a reboot kills the tunnel without anyone choosing that, and unless
 * Android's Always-on VPN is set nothing else brings it back: the switch simply stays off until the app is opened
 * (seen after several past updates). [AutoStartReceiver] reads this. Its own prefs file, so no rules listener fires.
 */
object VpnWanted {
    private const val FILE = "focusfence_vpn_state"
    private const val KEY = "wanted"

    fun get(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, wanted: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, wanted).apply()
    }
}
