/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.wallpaper.module;

import android.content.res.Resources;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Provides content from the partner customization on the device.
 */
public interface PartnerProvider {

    /**
     * Marker action used to discover partner.
     */
    String ACTION_PARTNER_CUSTOMIZATION =
            "com.android.launcher3.action.PARTNER_CUSTOMIZATION";

    /**
     * The resource ID in the partner APK for an xml describing collections and wallpapers.
     */
    String WALLPAPER_RES_ID = "wallpapers";

    /**
     * The resource ID in the partner APK for its list of wallpapers in legacy string-array format.
     */
    String LEGACY_WALLPAPER_RES_ID = "partner_wallpapers";

    /**
     * Directory for system wallpapers in legacy versions of the partner APK.
     */
    String RES_LEGACY_SYSTEM_WALLPAPER_DIR = "system_wallpaper_directory";

    /**
     * Boolean indicating the OEM does not want the picker to show the built-in Android system
     * wallpaper because they've provided their own wallpapers instead.
     * NOTE: The typo here "wallpapper" is intentional. The typo was made in legacy versions of the
     * customization scheme so we can't fix it without breaking existing devices.
     */
    String RES_DEFAULT_WALLPAPER_HIDDEN = "default_wallpapper_hidden";

    /**
     * A system APK advertising {@link #ACTION_PARTNER_CUSTOMIZATION}.
     */
    final class PartnerApk {
        public final String packageName;
        public final Resources resources;

        public PartnerApk(@NonNull String packageName, @NonNull Resources resources) {
            this.packageName = packageName;
            this.resources = resources;
        }
    }

    /**
     * Returns all system partner customization APKs. Devices may ship more than one (for example
     * OEM DerpWalls plus PixelWallpapers); callers that need complete wallpaper lists should
     * consult every entry rather than {@link #getResources()} alone.
     */
    @NonNull
    default List<PartnerApk> getPartnerApks() {
        Resources resources = getResources();
        String packageName = getPackageName();
        if (resources == null || packageName == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new PartnerApk(packageName, resources));
    }

    /**
     * Returns the Resources object for the primary partner APK, or null if there is no partner APK
     * on the device. Prefer {@link #getPartnerApks()} when aggregating wallpapers from multiple
     * partners.
     */
    @Nullable
    Resources getResources();

    /**
     * Returns the directory containing wallpapers, or null if the directory is not found on the
     * device. The directory is only present and populated in legacy versions of the partner
     * customization scheme.
     */
    File getLegacyWallpaperDirectory();

    /**
     * Returns the package name of the primary partner APK, or null if there is no partner APK on
     * the device.
     */
    @Nullable String getPackageName();

    /**
     * Refresh the resources due to Locale change.
     */
    void refreshResourcesDueToLocaleChange();

    /**
     * Returns whether any partner customization APK has specified that the built-in system default
     * wallpaper should be hidden. If no partner customization exists on the device, returns false.
     */
    boolean shouldHideDefaultWallpaper();
}
