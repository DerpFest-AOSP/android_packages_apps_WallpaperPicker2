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

package com.android.wallpaper.picker.wallpapers.data.repository

import android.content.Context
import android.util.Log
import com.android.wallpaper.model.WallpaperRotationInitializer
import com.android.wallpaper.picker.data.WallpaperModel
import com.android.wallpaper.picker.data.category.CategoryModel
import com.android.wallpaper.picker.di.modules.BackgroundDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
open class DefaultCategoryWallpapersRepository
@Inject
constructor(
    @ApplicationContext val context: Context,
    private val rotationInitializerFactory: RotationInitializerFactory,
    @BackgroundDispatcher private val backgroundScope: CoroutineScope,
    @BackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : CategoryWallpapersRepository {

    /** The selected [CategoryModel] */
    private val _selectedCategoryModel = MutableStateFlow<CategoryModel?>(null)
    override val selectedCategoryModel: StateFlow<CategoryModel?> =
        _selectedCategoryModel.asStateFlow()

    // to synchronize access to wallpapers cache
    private val cacheMutex = Mutex()

    /**
     * A [WallpaperRotationInitializer] which manages he initiation of wallpaper rotation for a
     * specific category
     */
    private var rotationInitializer: WallpaperRotationInitializer? = null

    /**
     * A mutable map that associates a unique [String] collection id with a [WallpaperModel] for the
     * category represented by the collection id.
     *
     * This map is used to cache wallpapers by their associated category's collection id identifier,
     * name, or any other unique string key.
     *
     * Example usage:
     * ```
     * wallpaperMap["beach"] = beachWallpaperModel
     * val mountainWallpaper = wallpaperMap["mountain"]
     * ```
     */
    private val wallpapersCache: MutableMap<String, List<WallpaperModel>> = mutableMapOf()

    // used to trigger a refresh of the category wallpapers when the category didn't change
    private val _refreshTrigger = MutableStateFlow(true)

    private val _isWallpapersFetching = MutableStateFlow<Boolean>(false)
    override val isWallpapersFetching: StateFlow<Boolean> = _isWallpapersFetching.asStateFlow()

    override val selectedCategoryWallpapers: StateFlow<List<WallpaperModel>> =
        _selectedCategoryModel
            .onEach { _isWallpapersFetching.value = true }
            .combine(_refreshTrigger) { category, _ -> category }
            .flatMapLatest { category ->
                if (category == null) {
                    return@flatMapLatest flow { emit(emptyList()) }
                }
                fetchWallpapersFlow(category.commonCategoryData.collectionId, category)
            }
            .distinctUntilChanged { oldList, newList ->
                val oldIds = oldList.map { it.commonWallpaperData.id.uniqueId }
                val newIds = newList.map { it.commonWallpaperData.id.uniqueId }

                return@distinctUntilChanged oldIds == newIds
            }
            .onEach { _isWallpapersFetching.value = false }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    /**
     * Helper function to fetch wallpapers, checking cache and performing network work. Returns a
     * standard Flow that emits the result once.
     */
    private fun fetchWallpapersFlow(
        collectionId: String,
        category: CategoryModel,
    ): Flow<List<WallpaperModel>> = flow {
        // Cache Check
        val cachedWallpapers = cacheMutex.withLock { wallpapersCache[collectionId] }
        if (!cachedWallpapers.isNullOrEmpty()) {
            emit(cachedWallpapers)
            return@flow // Cache hit, done
        }

        val result =
            withContext(backgroundDispatcher) {
                category.commonCategoryData.fetchWallpapers?.invoke(collectionId)
                    ?: category.collectionCategoryData?.wallpaperModels
            }
        val wallpapers = result ?: emptyList()

        // Update cache and emit the fetched value
        if (!wallpapers.isNullOrEmpty()) {
            cacheMutex.withLock { wallpapersCache[collectionId] = wallpapers }
        } else {
            purgeCache(collectionId)
        }
        emit(wallpapers)
    }

    override fun setSelectedCategory(category: CategoryModel) {
        _selectedCategoryModel.value = category

        if (category.commonCategoryData.isRotationEnabled) {
            rotationInitializer =
                rotationInitializerFactory.create(
                    category.commonCategoryData.collectionId,
                    category.commonCategoryData.title,
                )
        }
    }

    /**
     * Refreshes the wallpapers for the currently selected category. Purges the cache and manually
     * triggers the flow to restart by setting the category model again.
     */
    override fun refreshWallpapers() {
        _selectedCategoryModel.value?.let { category ->
            backgroundScope.launch {
                purgeCache(category.commonCategoryData.collectionId)
                _refreshTrigger.value = !_refreshTrigger.value
            }
        }
    }

    private suspend fun purgeCache(collectionId: String) {
        cacheMutex.withLock { wallpapersCache.remove(collectionId) }
    }

    override fun clearSelectedCategory() {
        _selectedCategoryModel.value = null
        rotationInitializer = null
    }

    override suspend fun startRotation(networkPreference: Int) =
        suspendCancellableCoroutine { cont ->
            val commonCategoryData = _selectedCategoryModel.value?.commonCategoryData

            if (commonCategoryData == null) {
                Log.e(TAG, "Wallpaper rotation failed to start. Category data is missing.")
                cont.resumeWithException(
                    IllegalStateException("Common category data is not initialized.")
                )
                return@suspendCancellableCoroutine
            }

            rotationInitializer?.setFirstWallpaperInRotation(
                context,
                networkPreference,
                object : WallpaperRotationInitializer.Listener {
                    override fun onFirstWallpaperInRotationSet() {
                        if (rotationInitializer!!.startRotation(context)) {
                            cont.resume(Unit)
                        } else {
                            Log.e(TAG, "Wallpaper rotation failed to start.")
                            cont.resumeWithException(RuntimeException())
                        }
                    }

                    override fun onError() {
                        Log.e(TAG, "Wallpaper initialization failed.")
                        cont.resumeWithException(RuntimeException())
                    }
                },
            )
        }

    override fun invalidateCache(categoryId: String) {
        wallpapersCache.remove(categoryId)
        // when a CRUD operation happens to any category this may trigger a change in other
        // live wallpaper categories. For example, when selecting a new wallpaper, the content
        // providers for other live wallpapers will update their data to reflect that change (i.e.
        // WallpaperModelContract.WALLPAPER_IS_APPLIED)
        val iterator = wallpapersCache.iterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val wallpaperList = entry.value

            val shouldRemoveCategory =
                wallpaperList.any { model -> (model as? WallpaperModel.LiveWallpaperModel) != null }

            if (shouldRemoveCategory) {
                iterator.remove()
            }
        }
    }

    companion object {
        const val TAG = "DefaultCategoryWallpapersRepository"
    }
}
