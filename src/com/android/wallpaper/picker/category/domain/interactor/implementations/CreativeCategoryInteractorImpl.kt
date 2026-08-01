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
import android.util.Log
import com.android.wallpaper.config.BaseFlags
import com.android.wallpaper.model.CreativeCategory
import com.android.wallpaper.module.DefaultExtendedEffectsHelper
import com.android.wallpaper.module.ExtendedEffectsHelper
import com.android.wallpaper.picker.category.domain.interactor.CreativeCategoryInteractor
import com.android.wallpaper.picker.data.WallpaperModel
import com.android.wallpaper.picker.data.category.CategoryModel
import com.android.wallpaper.picker.data.category.CommonCategoryData
import com.android.wallpaper.util.converter.category.CategoryFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.xmlpull.v1.XmlPullParserException

/**
 * Discovers Pixel creative wallpaper categories (AI, Emoji, etc.) from installed
 * WALLPAPER_CREATION services and loads their templates from package content providers.
 *
 * Magic Portrait is exposed separately via [standaloneCategories] and must go through the
 * photo-picker / extended-effects flow rather than live-wallpaper preview.
 */
@Singleton
class CreativeCategoryInteractorImpl
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val categoryFactory: CategoryFactory,
    private val extendedEffectsHelper: ExtendedEffectsHelper,
) : CreativeCategoryInteractor {

    private fun discoverCreativeCategories(): List<CategoryModel> {
        val pm = context.packageManager
        val creationIntent = Intent(WALLPAPER_CREATION_ACTION)
        val services = pm.queryIntentServices(creationIntent, PackageManager.GET_META_DATA)

        val categories = mutableListOf<CategoryModel>()
        val seenCollectionIds = mutableSetOf<String>()
        val magicPortraitPackage =
            extendedEffectsHelper.effectsPackage.ifEmpty {
                DefaultExtendedEffectsHelper.MAGIC_PORTRAIT_PACKAGE
            }

        for (resolveInfo in services) {
            val packageName = resolveInfo.serviceInfo.packageName
            // Magic Portrait has WALLPAPER_CREATION but no content-provider templates; previewing
            // it as a creative/live wallpaper crashes with a null effectType description.
            if (packageName == magicPortraitPackage) {
                continue
            }

            val wallpaperInfo =
                try {
                    android.app.WallpaperInfo(context, resolveInfo)
                } catch (e: XmlPullParserException) {
                    Log.w(TAG, "Skipping wallpaper ${resolveInfo.serviceInfo}", e)
                    continue
                } catch (e: IOException) {
                    Log.w(TAG, "Skipping wallpaper ${resolveInfo.serviceInfo}", e)
                    continue
                }

            val metaData = wallpaperInfo.serviceInfo.metaData ?: continue
            if (
                metaData.get(CreativeCategory.KEY_WALLPAPER_CREATIVE_CATEGORY) == null ||
                    metaData.get(CreativeCategory.KEY_WALLPAPER_CREATIVE_WALLPAPERS) == null
            ) {
                continue
            }

            val creativeCategories =
                CreativeCategory.readCreativeCategories(context, wallpaperInfo) ?: continue

            for (category in creativeCategories) {
                if (!seenCollectionIds.add(category.collectionId)) {
                    continue
                }
                val model = categoryFactory.getCategoryModel(category)
                if (!hasUsableCreativeContent(model)) {
                    Log.w(
                        TAG,
                        "Omitting creative category ${category.collectionId}: missing templates",
                    )
                    continue
                }
                categories.add(model)
            }
        }

        return categories.sortedBy { it.commonCategoryData.priority }
    }

    /**
     * Require collection data with at least one wallpaper that carries description content, so
     * WallpaperEditorActivity is not launched with an empty wp_description.
     */
    private fun hasUsableCreativeContent(model: CategoryModel): Boolean {
        val collection = model.collectionCategoryData ?: return false
        if (collection.wallpaperModels.isEmpty()) {
            return false
        }
        return collection.wallpaperModels.any { wallpaper ->
            val live = wallpaper as? WallpaperModel.LiveWallpaperModel ?: return@any false
            !live.liveWallpaperData.description.content.keySet().isEmpty()
        }
    }

    private fun discoverStandaloneCategories(): List<CategoryModel> {
        if (!BaseFlags.get(context).isMagicPortraitEntryPointsEnabled()) {
            return emptyList()
        }
        val packageName =
            extendedEffectsHelper.effectsPackage.ifEmpty {
                DefaultExtendedEffectsHelper.MAGIC_PORTRAIT_PACKAGE
            }
        val effectsIntent = extendedEffectsHelper.getExtendedEffectIntent()
        if (effectsIntent.resolveActivityInfo(context.packageManager, 0) == null) {
            return emptyList()
        }

        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            val title = context.packageManager.getApplicationLabel(appInfo).toString()
            val icon = context.packageManager.getApplicationIcon(appInfo)
            listOf(
                CategoryModel(
                    commonCategoryData =
                        CommonCategoryData(
                            title = title,
                            collectionId = MAGIC_PORTRAIT_COLLECTION_ID,
                            priority = PRIORITY_STANDALONE,
                            thumbnailDrawable = icon,
                        )
                )
            )
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "Magic Portrait package not installed: $packageName")
            emptyList()
        }
    }

    override val categories: Flow<List<CategoryModel>> = flowOf(discoverCreativeCategories())

    override val standaloneCategories: Flow<List<CategoryModel>> =
        flowOf(discoverStandaloneCategories())

    override fun updateCreativeCategories() {
        // Categories are discovered at initialization. Refresh broadcasts can be added later.
    }

    override fun updatePackThemeCategory() {}

    companion object {
        private const val TAG = "CreativeCategoryInteractorImpl"
        private const val WALLPAPER_CREATION_ACTION =
            "com.google.android.apps.wallpaper.action.WALLPAPER_CREATION"
        private const val MAGIC_PORTRAIT_COLLECTION_ID = "magic_portrait"
        private const val PRIORITY_STANDALONE = 50
    }
}
