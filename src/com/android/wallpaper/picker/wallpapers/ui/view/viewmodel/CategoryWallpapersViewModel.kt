/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wallpaper.picker.wallpapers.ui.view.viewmodel

import android.app.WallpaperInfo
import android.app.WallpaperManager
import android.app.WallpaperManager.FLAG_LOCK
import android.app.WallpaperManager.FLAG_SYSTEM
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.util.ArraySet
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.wallpaper.config.BaseFlags
import com.android.wallpaper.model.WallpaperRotationInitializer.NETWORK_PREFERENCE_CELLULAR_OK
import com.android.wallpaper.model.WallpaperRotationInitializer.NETWORK_PREFERENCE_WIFI_ONLY
import com.android.wallpaper.module.MultiPanesChecker
import com.android.wallpaper.module.WallpaperPreferences
import com.android.wallpaper.picker.common.preview.data.repository.PersistentWallpaperModelRepository
import com.android.wallpaper.picker.data.WallpaperModel
import com.android.wallpaper.picker.preview.ui.WallpaperPreviewActivity
import com.android.wallpaper.picker.wallpapers.domain.interactor.CategoryWallpapersInteractor
import com.android.wallpaper.util.LaunchSourceUtils.WALLPAPER_LAUNCH_SOURCE
import com.android.wallpaper.util.LiveWallpaperModuleDownloads
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * [CategoryWallpapersViewModel] is responsible for preparing and managing UI-related data for the
 * Wallpapers screen in a lifecycle-aware manner.
 */
@HiltViewModel
class CategoryWallpapersViewModel
@Inject
constructor(
    private val categoryWallpapersInteractor: CategoryWallpapersInteractor,
    private val persistentWallpaperModelRepository: PersistentWallpaperModelRepository,
    private val wallpaperManager: WallpaperManager,
    private val wallpaperPreferences: WallpaperPreferences,
    private val multiPanesChecker: MultiPanesChecker,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val launchSource: String? = savedStateHandle[WALLPAPER_LAUNCH_SOURCE]

    private val _showRotationDialog = MutableStateFlow(false)
    /**
     * [StateFlow] indicating whether the rotation setup dialog is currently visible.
     *
     * `true` when the user has clicked to start rotation and the confirmation dialog is displayed.
     */
    val showRotationDialog: StateFlow<Boolean> = _showRotationDialog

    private val _isRotationLoading = MutableStateFlow(false)

    /**
     * [StateFlow] indicating whether the wallpaper rotation process is currently executing
     *
     * When `true`, the dialog content should typically show a [CircularProgressIndicator] and
     * disable action buttons.
     */
    val isRotationLoading: StateFlow<Boolean> = _isRotationLoading

    private val _networkPreference = MutableStateFlow(NETWORK_PREFERENCE_WIFI_ONLY)
    /**
     * [StateFlow] representing the user's current network preference for downloading wallpapers.
     *
     * This value is typically used to control a checkbox in the rotation dialog. The value follows
     * an integer convention:
     * - `NETWORK_PREFERENCE_WIFI_ONLY` (often 0): Synchronization only occurs over Wi-Fi.
     * - Non-zero value (often 1): Synchronization is allowed over any network (Wi-Fi or cellular).
     */
    val networkPreference: StateFlow<Int> = _networkPreference

    /**
     * This [Flow] emits [CategoryWallpapersContentViewModel] by mapping the [List<WallpaperModel>]
     * of a category to [List<CategoryWallpapersItemViewModel>]
     */
    val categoryWallpapersContentViewModel: Flow<CategoryWallpapersContentViewModel> =
        combine(
            categoryWallpapersInteractor.selectedCategoryWallpapers,
            categoryWallpapersInteractor.categoryTitle,
            categoryWallpapersInteractor.isRotationEnabled,
        ) { wallpapers, title, isRotationEnabled ->
            if (DEBUG) {
                Log.d(TAG, "WallpaperModels: ${wallpapers.size}")
            }

            val groupedWallpapers =
                wallpapers
                    .groupBy { wallpaper ->
                        val groupName =
                            when (wallpaper) {
                                is WallpaperModel.LiveWallpaperModel ->
                                    wallpaper.liveWallpaperData.groupName
                                is WallpaperModel.StaticWallpaperModel ->
                                    wallpaper.downloadableWallpaperData?.groupName
                                else -> null
                            }

                        if (groupName.isNullOrEmpty()) {
                            DEFAULT_GROUP
                        } else {
                            groupName
                        }
                    }
                    .toMutableMap()

            if (DEBUG) {
                Log.d(TAG, "Wallpaper groups: ")
                for ((groupName, wallpapers) in groupedWallpapers) {
                    Log.d(TAG, "Group NAME: ${groupName}")
                    for (wallpaper in wallpapers) {
                        Log.d(TAG, "${wallpaper}")
                    }
                }
            }

            val firstEntry = groupedWallpapers.keys.firstOrNull()

            val templates = buildList {
                if (firstEntry != null && isTemplateGroup(firstEntry, groupedWallpapers)) {
                    add(CategoryWallpapersItemViewModel.PrimaryHeaderViewModelCategory(firstEntry))
                    val items =
                        groupedWallpapers[firstEntry]?.map {
                            CategoryWallpapersItemViewModel.ThumbnailsViewModelCategory(
                                thumbnailAsset = it.commonWallpaperData.thumbAsset,
                                title = it.commonWallpaperData.title,
                                contentDescription = it.commonWallpaperData.title,
                                getLaunchActivityIntent = {
                                    persistentWallpaperModelRepository.setWallpaperModel(it)
                                    val previewIntent =
                                        WallpaperPreviewActivity.intentBuilder(context, true)
                                            .refreshCategory(true)
                                            .newTask(multiPanesChecker.isMultiPanesEnabled(context))
                                            .navigateToExtendedEffects(false)
                                            .wallpaperLaunchSource(launchSource)
                                            .build()
                                    return@ThumbnailsViewModelCategory previewIntent
                                },
                            )
                        }
                    items?.let {
                        add(CategoryWallpapersItemViewModel.TemplateThumbnailsViewModelCategory(it))
                        groupedWallpapers.remove(firstEntry)
                    }
                }
            }

            val wallpaperItems = buildList {
                for ((groupName, wallpapers) in groupedWallpapers) {
                    if (groupName != DEFAULT_GROUP) {
                        add(
                            CategoryWallpapersItemViewModel.SecondaryHeaderViewModelCategory(
                                groupName
                            )
                        )
                    }
                    val currentHomeWallpaper: android.app.WallpaperInfo? =
                        wallpaperManager.getWallpaperInfo(FLAG_SYSTEM)
                    val currentLockWallpaper: android.app.WallpaperInfo? =
                        wallpaperManager.getWallpaperInfo(FLAG_LOCK)
                    val appliedWallpaperIds = getAppliedWallpaperIds()

                    val items =
                        wallpapers.map {
                            CategoryWallpapersItemViewModel.ThumbnailsViewModelCategory(
                                thumbnailAsset = it.commonWallpaperData.thumbAsset,
                                title = it.commonWallpaperData.title,
                                contentDescription = getContentDescriptionFromWallpaperModel(it),
                                isApplied =
                                    if (it is WallpaperModel.LiveWallpaperModel) {
                                        it.isApplied(currentHomeWallpaper, currentLockWallpaper)
                                    } else {
                                        appliedWallpaperIds.contains(
                                            it.commonWallpaperData.id.uniqueId
                                        )
                                    },
                                isDownloadable =
                                    (it as? WallpaperModel.StaticWallpaperModel)
                                        ?.downloadableWallpaperData != null ||
                                        moduleDownloadIntent(it) != null,
                                getLaunchActivityIntent = {
                                    // A live wallpaper whose assets aren't downloaded yet can't be
                                    // previewed: open its app's download screen instead.
                                    moduleDownloadIntent(it)?.let { downloadIntent ->
                                        return@ThumbnailsViewModelCategory downloadIntent
                                    }
                                    persistentWallpaperModelRepository.setWallpaperModel(it)
                                    val previewIntent =
                                        WallpaperPreviewActivity.intentBuilder(context, true)
                                            .refreshCategory(true)
                                            .newTask(multiPanesChecker.isMultiPanesEnabled(context))
                                            .navigateToExtendedEffects(false)
                                            .wallpaperLaunchSource(launchSource)
                                            .build()
                                    return@ThumbnailsViewModelCategory previewIntent
                                },
                            )
                        }
                    val isResizeable: Boolean =
                        ((wallpapers.getOrNull(0) as? WallpaperModel.LiveWallpaperModel)
                            ?.creativeWallpaperData == null) && groupedWallpapers.size == 1

                    val areTilesLarge: Boolean =
                        (isResizeable && items.size <= MIN_THUMBNAILS_RESIZE_GRID)

                    add(
                        CategoryWallpapersItemViewModel.PlainThumbnailsViewModelCategory(
                            items,
                            areTilesLarge,
                        )
                    )
                }
            }

            if (DEBUG) {
                Log.d(TAG, "here is the list of wallpaperItems yo: ${wallpaperItems}")
            }

            val isNewUIEnabled =
                BaseFlags.get(context).isCollabsableSectionInAiEnabled() &&
                    !templates.isNullOrEmpty()

            val wallpaperItem =
                if (isNewUIEnabled) {
                    wallpaperItems + templates
                } else {
                    templates + wallpaperItems
                }

            return@combine CategoryWallpapersContentViewModel(
                rotationEnabled = isRotationEnabled,
                isNewUIEnabled = isNewUIEnabled,
                onRotationStart = { startRotation() },
                onShowRotationDialog = { onRotationStartClick() },
                onCancelRotationDialog = { onCancelRotationDialog() },
                onNetworkPreferences = { isWifiOnly -> updateNetworkPreferences(isWifiOnly) },
                title = title,
                wallpaperItems = wallpaperItem,
                dismissScreen = { _dismissScreenEvent.emit(Unit) },
            )
        }

    /** This method identifies whether the group of wallpapers are templates */
    private fun isTemplateGroup(
        groupId: String?,
        groupedWallpapers: Map<String, List<WallpaperModel>>,
    ): Boolean {
        if (groupId == null || groupId == DEFAULT_GROUP) return false

        val groupList = groupedWallpapers[groupId] ?: return false

        // Check if the group has multiple items and the first item is a new creative template
        val firstItem = groupList.firstOrNull() as? WallpaperModel.LiveWallpaperModel
        val isNewCreative = firstItem?.creativeWallpaperData?.isNewCreativeWallpaper == true

        return groupList.size > 1 && isNewCreative
    }

    /**
     * A [Flow] that emits the loading state for fetching wallpapers of the currently selected
     * category.
     *
     * Emits `true` when a request to fetch wallpapers is active, and `false` otherwise. This flow
     * is typically observed by the UI to show or hide a loading indicator.
     */
    val categoryWallpapersIsLoading: Flow<Boolean> =
        categoryWallpapersInteractor.isWallpapersFetching

    override fun onCleared() {
        super.onCleared()
        categoryWallpapersInteractor.clearSelectedCategory()
    }

    /**
     * Retrieves a suitable content description for the given [wallpaperModel].
     *
     * The selection logic follows this priority:
     * 1. Returns the wallpaper title if available.
     * 2. Returns the first attribution string if available.
     * 3. Defaults to an empty string if no description can be determined.
     *
     * @param wallpaperModel The model containing wallpaper metadata.
     * @return A [String] description or an empty string.
     */
    private fun getContentDescriptionFromWallpaperModel(wallpaperModel: WallpaperModel): String {
        // TODO(b/476145304): add default wallpaper content description
        return wallpaperModel.commonWallpaperData.title
            ?: wallpaperModel.commonWallpaperData.attributions?.firstOrNull()
            ?: ""
    }

    private fun startRotation() {
        viewModelScope.launch {
            _isRotationLoading.value = true
            try {
                categoryWallpapersInteractor.startRotation(_networkPreference.value)
                _dismissScreenEvent.emit(Unit)
            } catch (e: Throwable) {
                Log.e(TAG, "Failure starting wallpaper rotation")
            } finally {
                _isRotationLoading.value = false
                _showRotationDialog.value = false
                _networkPreference.value = NETWORK_PREFERENCE_WIFI_ONLY
            }
        }
    }

    private fun onRotationStartClick() {
        _showRotationDialog.value = true
    }

    private fun onCancelRotationDialog() {
        _showRotationDialog.value = false
        _networkPreference.value = NETWORK_PREFERENCE_WIFI_ONLY
    }

    private val _dismissScreenEvent = MutableSharedFlow<Unit>() // one-time signal
    val dismissScreenEvent: SharedFlow<Unit> = _dismissScreenEvent

    private fun updateNetworkPreferences(isWifi: Boolean) {
        _networkPreference.value =
            if (isWifi) NETWORK_PREFERENCE_WIFI_ONLY else NETWORK_PREFERENCE_CELLULAR_OK
    }

    // TODO(b/444284275): remove references to remote ids
    private fun getAppliedWallpaperIds(): Set<String> {
        val wallpaperInfo = wallpaperManager?.wallpaperInfo
        val appliedWallpaperIds: MutableSet<String> = ArraySet()
        val homeWallpaperId =
            if (wallpaperInfo != null) {
                wallpaperInfo.serviceName
            } else {
                wallpaperPreferences.getHomeWallpaperRemoteId()
            }
        if (!homeWallpaperId.isNullOrEmpty()) {
            appliedWallpaperIds.add(homeWallpaperId)
        }
        val isLockWallpaperApplied =
            wallpaperManager!!.getWallpaperId(WallpaperManager.FLAG_LOCK) >= 0
        val lockWallpaperId = wallpaperPreferences.getLockWallpaperRemoteId()
        if (isLockWallpaperApplied && !lockWallpaperId.isNullOrEmpty()) {
            appliedWallpaperIds.add(lockWallpaperId)
        }
        return appliedWallpaperIds
    }

    private fun WallpaperModel.LiveWallpaperModel.isApplied(
        currentHomeWallpaper: WallpaperInfo?,
        currentLockWallpaper: WallpaperInfo?,
    ): Boolean {
        val component: WallpaperInfo = liveWallpaperData.systemWallpaperInfo
        val serviceName = component.serviceName
        val isAppliedToHome =
            currentHomeWallpaper != null &&
                TextUtils.equals(currentHomeWallpaper.serviceName, serviceName)
        val isAppliedToLock =
            currentLockWallpaper != null &&
                TextUtils.equals(currentLockWallpaper.serviceName, serviceName)

        return if (creativeWallpaperData != null) {
            ((isAppliedToHome || isAppliedToLock) && creativeWallpaperData.isCurrent)
        } else {
            isAppliedToHome || isAppliedToLock
        }
    }

    private fun moduleDownloadIntent(wallpaper: WallpaperModel): Intent? =
        (wallpaper as? WallpaperModel.LiveWallpaperModel)?.let {
            LiveWallpaperModuleDownloads.downloadIntent(
                context,
                it.liveWallpaperData.systemWallpaperInfo,
            )
        }

    companion object {
        const val DEBUG = false
        const val TAG = "CategoryWallpapersViewModel"
        const val DEFAULT_GROUP = "default_group"

        const val MIN_THUMBNAILS_RESIZE_GRID: Int = 8
    }
}
