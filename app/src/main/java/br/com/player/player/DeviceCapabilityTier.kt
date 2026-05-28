package br.com.player.player

import android.app.ActivityManager
import android.content.Context

object DeviceCapabilityTier {

    enum class Tier { HIGH, MID, LOW }

    fun assess(context: Context): Tier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return when {
            memInfo.lowMemory -> Tier.LOW
            memInfo.totalMem < 2L * 1024L * 1024L * 1024L -> Tier.MID
            else -> Tier.HIGH
        }
    }

    fun Tier.maxPreloadDistance(): Int = when (this) {
        Tier.LOW -> 1
        Tier.MID -> 2
        Tier.HIGH -> Int.MAX_VALUE
    }
}
