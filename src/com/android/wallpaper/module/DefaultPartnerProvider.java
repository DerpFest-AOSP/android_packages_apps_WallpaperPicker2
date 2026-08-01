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

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dagger.hilt.android.qualifiers.ApplicationContext;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides content from partner customization APKs on the device.
 *
 * <p>Multiple system apps may advertise {@link PartnerProvider#ACTION_PARTNER_CUSTOMIZATION}
 * (e.g. DerpWalls with legacy {@code partner_wallpapers} and PixelWallpapers with modern
 * {@code wallpapers.xml}). This provider exposes all of them so wallpaper lists can be merged,
 * instead of keeping only the first match.
 */
@Singleton
public class DefaultPartnerProvider implements PartnerProvider {
    private static final String TAG = "DefaultPartnerProvider";

    private final Context mContext;

    private List<PartnerApk> mPartnerApks = Collections.emptyList();
    @Nullable private String mPackageName;
    @Nullable private Resources mResources;

    @Inject
    public DefaultPartnerProvider(@ApplicationContext Context ctx) {
        mContext = ctx;
        refreshPartnerApks();
    }

    @NonNull
    @Override
    public List<PartnerApk> getPartnerApks() {
        return mPartnerApks;
    }

    /**
     * Finds all system partner customization APKs.
     */
    @NonNull
    protected List<PartnerApk> findSystemApks(PackageManager pm) {
        final Intent intent = new Intent(PartnerProvider.ACTION_PARTNER_CUSTOMIZATION);
        List<PartnerApk> partners = new ArrayList<>();
        for (ResolveInfo info : pm.queryBroadcastReceivers(intent, 0)) {
            if (info.activityInfo == null
                    || (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM)
                    == 0) {
                continue;
            }
            final String packageName = info.activityInfo.packageName;
            try {
                final Resources res = pm.getResourcesForApplication(packageName);
                partners.add(new PartnerApk(packageName, res));
            } catch (NameNotFoundException e) {
                Log.w(TAG, "Failed to find resources for " + packageName);
            }
        }
        return partners;
    }

    private void refreshPartnerApks() {
        mPartnerApks = Collections.unmodifiableList(
                findSystemApks(mContext.getPackageManager()));
        PartnerApk primary = selectPrimaryPartner(mPartnerApks);
        if (primary != null) {
            mPackageName = primary.packageName;
            mResources = primary.resources;
        } else {
            mPackageName = null;
            mResources = null;
        }
    }

    /**
     * Prefer a partner that ships legacy {@code partner_wallpapers} (OEM bundles like DerpWalls),
     * then one with modern {@code wallpapers.xml}, otherwise the first discovered APK.
     */
    @Nullable
    private static PartnerApk selectPrimaryPartner(List<PartnerApk> partners) {
        for (PartnerApk apk : partners) {
            if (apk.resources.getIdentifier(
                    LEGACY_WALLPAPER_RES_ID, "array", apk.packageName) != 0) {
                return apk;
            }
        }
        for (PartnerApk apk : partners) {
            if (apk.resources.getIdentifier(WALLPAPER_RES_ID, "xml", apk.packageName) != 0) {
                return apk;
            }
        }
        return partners.isEmpty() ? null : partners.get(0);
    }

    @Override
    public void refreshResourcesDueToLocaleChange() {
        refreshPartnerApks();
    }

    @Override
    @Nullable
    public Resources getResources() {
        return mResources;
    }

    @Override
    public File getLegacyWallpaperDirectory() {
        for (PartnerApk apk : mPartnerApks) {
            final int resId = apk.resources.getIdentifier(
                    PartnerProvider.RES_LEGACY_SYSTEM_WALLPAPER_DIR, "string", apk.packageName);
            if (resId != 0) {
                return new File(apk.resources.getString(resId));
            }
        }
        return null;
    }

    @Override
    @Nullable
    public String getPackageName() {
        return mPackageName;
    }

    @Override
    public boolean shouldHideDefaultWallpaper() {
        for (PartnerApk apk : mPartnerApks) {
            final int resId = apk.resources.getIdentifier(
                    RES_DEFAULT_WALLPAPER_HIDDEN, /* defType */ "bool", apk.packageName);
            if (resId != 0 && apk.resources.getBoolean(resId)) {
                return true;
            }
        }
        return false;
    }
}
