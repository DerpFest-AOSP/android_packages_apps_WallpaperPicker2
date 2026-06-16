/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wallpaper.picker.category.domain.interactor.implementations

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.android.wallpaper.picker.category.domain.interactor.CreativeCategoryInteractor
import com.android.wallpaper.picker.data.category.CategoryModel
import com.android.wallpaper.picker.data.category.CommonCategoryData
import com.android.wallpaper.picker.data.category.ThirdPartyCategoryData
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Pixel-specific implementation that discovers creative wallpaper categories
 * (AI Wallpapers, Emoji Wallpapers, Effects, etc.) from installed Pixel wallpaper packages.
 */
@Singleton
class CreativeCategoryInteractorImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : CreativeCategoryInteractor {

    private fun discoverCreativeCategories(): List<CategoryModel> {
        val pm = context.packageManager
        val categories = mutableListOf<CategoryModel>()

        // Discover live wallpaper services that have the WALLPAPER_CREATION action
        // These are Pixel creative wallpaper services (AI, Emoji, etc.)
        val creationIntent = Intent("com.google.android.apps.wallpaper.action.WALLPAPER_CREATION")
        val services = pm.queryIntentServices(creationIntent, PackageManager.GET_META_DATA)

        for (service in services) {
            val packageName = service.serviceInfo.packageName
            val label = service.loadLabel(pm).toString()
            val icon = service.loadIcon(pm)

            // Create a resolve info that launches the service's package
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                val activities = pm.queryIntentActivities(launchIntent, 0)
                if (activities.isNotEmpty()) {
                    val resolveInfo = activities[0]
                    categories.add(
                        CategoryModel(
                            commonCategoryData = CommonCategoryData(
                                title = label,
                                collectionId = "creative_$packageName",
                                priority = PRIORITY_CREATIVE,
                            ),
                            thirdPartyCategoryData = ThirdPartyCategoryData(
                                resolveInfo = resolveInfo,
                                defaultDrawable = icon,
                            ),
                        )
                    )
                }
            }
        }

        // Also check for known Pixel wallpaper packages that may not use WALLPAPER_CREATION
        for ((pkg, fallbackLabel) in KNOWN_PIXEL_PACKAGES) {
            if (categories.any { it.thirdPartyCategoryData?.resolveInfo?.activityInfo?.packageName == pkg }) {
                continue // Already discovered
            }
            val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: continue
            val activities = pm.queryIntentActivities(launchIntent, 0)
            if (activities.isNotEmpty()) {
                val resolveInfo = activities[0]
                val appInfo = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
                val icon = appInfo?.loadIcon(pm)
                val label = appInfo?.loadLabel(pm)?.toString() ?: fallbackLabel
                categories.add(
                    CategoryModel(
                        commonCategoryData = CommonCategoryData(
                            title = label,
                            collectionId = "creative_$pkg",
                            priority = PRIORITY_CREATIVE,
                        ),
                        thirdPartyCategoryData = ThirdPartyCategoryData(
                            resolveInfo = resolveInfo,
                            defaultDrawable = icon,
                        ),
                    )
                )
            }
        }

        return categories
    }

    override val categories: Flow<List<CategoryModel>> = flowOf(discoverCreativeCategories())

    override val standaloneCategories: Flow<List<CategoryModel>> = flowOf(emptyList())

    override fun updateCreativeCategories() {
        // Categories are discovered at initialization
    }

    override fun updatePackThemeCategory() {}

    companion object {
        private const val PRIORITY_CREATIVE = 100

        // Known Pixel creative wallpaper packages
        private val KNOWN_PIXEL_PACKAGES = mapOf(
            "com.google.android.apps.aiwallpapers" to "AI Wallpapers",
            "com.google.android.apps.emojiwallpaper" to "Emoji Wallpapers",
            "com.google.android.wallpaper.effects" to "Wallpaper Effects",
            "com.google.android.apps.wallpaper.pixel" to "Pixel Wallpapers",
        )
    }
}
