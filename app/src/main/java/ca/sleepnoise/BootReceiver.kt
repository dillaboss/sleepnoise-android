package ca.sleepnoise

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot: start the service (sound, voice, schedule) and show the screen. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        SleepService.start(context)
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED)
            runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
