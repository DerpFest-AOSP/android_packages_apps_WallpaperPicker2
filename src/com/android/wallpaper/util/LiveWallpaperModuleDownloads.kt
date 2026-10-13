/*
 * SPDX-FileCopyrightText: 2026 ergdevops
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.wallpaper.util

import android.app.WallpaperInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Pixel Live Wallpapers (com.google.pixel.livewallpaper) ships most of its wallpapers disabled.
 * Each one's scenes and textures are a Play feature module, named in the service's
 * [META_MODULE] meta-data, that the app downloads the first time the wallpaper is picked. Once the
 * module is installed, the app enables the wallpaper service.
 *
 * Google's picker starts that download. This picker lists the disabled services
 * (MATCH_DISABLED_COMPONENTS) but had nothing to start it, so these wallpapers could never be
 * previewed or applied. [downloadIntent] returns the intent for the app's own download screen,
 * which takes the [WallpaperInfo] and shows the download and its progress.
 */
object LiveWallpaperModuleDownloads {

    private const val META_MODULE = "com.google.pixel.livewallpaper.module"
    private const val ACTION_DOWNLOAD =
        "com.google.pixel.livewallpaper.action.DOWNLOAD_LIVE_WALLPAPER"
    private const val EXTRA_LIVE_WALLPAPER_INFO = "android.live_wallpaper.info"
    // Both the download screen and a headless activity handle ACTION_DOWNLOAD; prefer the screen.
    private const val DOWNLOAD_ACTIVITY_SUFFIX = ".split.DownloadActivity"

    /**
     * Returns the intent that downloads [info]'s module, or null if the wallpaper doesn't use
     * one, is already enabled, or its package has no download screen.
     */
    fun downloadIntent(context: Context, info: WallpaperInfo): Intent? {
        val service = info.serviceInfo
        if (service.metaData?.getString(META_MODULE).isNullOrEmpty()) return null
        val pm = context.packageManager
        if (isEnabled(pm, info.component, service.enabled)) return null

        val intent = Intent(ACTION_DOWNLOAD).setPackage(info.packageName)
        val activities = pm.queryIntentActivities(intent, 0)
        val activity =
            (activities.firstOrNull { it.activityInfo.name.endsWith(DOWNLOAD_ACTIVITY_SUFFIX) }
                    ?: activities.firstOrNull())
                ?.activityInfo ?: return null
        return intent
            .setClassName(activity.packageName, activity.name)
            .putExtra(EXTRA_LIVE_WALLPAPER_INFO, info)
    }

    private fun isEnabled(
        pm: PackageManager,
        component: ComponentName,
        enabledInManifest: Boolean,
    ): Boolean =
        try {
            when (pm.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> enabledInManifest
                else -> false
            }
        } catch (e: IllegalArgumentException) {
            // Unknown component: leave it to the normal preview path.
            true
        }
}
