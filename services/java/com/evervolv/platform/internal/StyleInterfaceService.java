/*
 * Copyright (c) 2018 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evervolv.platform.internal;

import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import com.android.server.SystemService;

import evervolv.app.ContextConstants;
import evervolv.provider.EVSettings;
import evervolv.style.IStyleInterface;
import evervolv.style.StyleInterface;
import evervolv.style.Suggestion;
import evervolv.util.palette.Palette;

import java.util.ArrayList;
import java.util.List;

/** @hide */
public class StyleInterfaceService extends VendorService {
    private static final String TAG = "StyleInterfaceService";
    private static final String ACCENT_METADATA_COLOR = "berry_accent_preview";
    private static final int COLOR_DEFAULT = Color.BLACK;

    private Context mContext;
    private IOverlayManager mOverlayService;
    private PackageManager mPackageManager;

    private static final int OVERLAY_SYSTEM = 0;
    private static final int OVERLAY_ACCENT = 1;

    public StyleInterfaceService(Context context) {
        super(context);
        mContext = context;
        if (context.getPackageManager().hasSystemFeature(ContextConstants.Features.STYLES)) {
            publishBinderService(ContextConstants.STYLE_INTERFACE, mService);
        } else {
            Log.wtf(TAG, "Style service started by system server but feature xml not" +
                    " declared. Not publishing binder service!");
        }
    }

    @Override
    public String getFeatureDeclaration() {
        return ContextConstants.Features.STYLES;
    }

    @Override
    public void onStart() {
        /* No-op */
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mPackageManager = mContext.getPackageManager();
            mOverlayService = IOverlayManager.Stub.asInterface(ServiceManager.getService("overlay"));
        }
    }

    private void enforceChangeStylePermission() {
        mContext.enforceCallingOrSelfPermission(StyleInterface.CHANGE_STYLE_SETTINGS_PERMISSION,
                "You do not have permissions to change system style");
    }

    /* Public methods implementation */

    private boolean setGlobalStyleInternal(int mode, String packageName) {
        // Check whether the packageName is valid
        if (isAValidPackage(packageName)) {
            throw new IllegalArgumentException(packageName + " is not a valid package name!");
        }

        boolean statusValue = EVSettings.System.putInt(mContext.getContentResolver(),
                EVSettings.System.BERRY_GLOBAL_STYLE, mode);
        boolean packageNameValue = EVSettings.System.putString(mContext.getContentResolver(),
                EVSettings.System.BERRY_MANAGED_BY_APP, packageName);
        return  statusValue && packageNameValue;
    }

    private int getGlobalStyleInternal() {
        return EVSettings.System.getInt(mContext.getContentResolver(),
                EVSettings.System.BERRY_GLOBAL_STYLE,
                StyleInterface.STYLE_GLOBAL_AUTO_WALLPAPER);
    }

    private boolean setAccentInternal(String pkgName) {
        if (!isChangeableOverlay(pkgName)) {
            Log.e(TAG, pkgName + ": is not a valid overlay package");
            return false;
        }

        return setOverlayInternal(OVERLAY_ACCENT, pkgName);
    }

    private String getAccentInternal() {
        return getOverlayInternal(OVERLAY_ACCENT);
    }

    private Suggestion getSuggestionInternal(Bitmap source, int[] colors) {
        Palette palette = Palette.from(source).generate();

        // Extract dominant color
        int sourceColor = palette.getVibrantColor(COLOR_DEFAULT);
        // If vibrant color extraction failed, let's try muted color
        if (sourceColor == COLOR_DEFAULT) {
            sourceColor = palette.getMutedColor(COLOR_DEFAULT);
        }

        boolean isLight = Color.luminance(sourceColor) > 0.3;
        int bestColorPosition = getBestColor(sourceColor, colors);
        int suggestedGlobalStyle = isLight ?
                StyleInterface.STYLE_GLOBAL_LIGHT : StyleInterface.STYLE_GLOBAL_DARK;
        return new Suggestion(suggestedGlobalStyle, bestColorPosition);
    }

    private List<String> getTrustedAccentsInternal() {
        List<String> results = new ArrayList<>();
        String[] packages = mContext.getResources()
                .getStringArray(R.array.trusted_accent_packages);

        results.add(StyleInterface.ACCENT_DEFAULT);
        for (String item : packages) {
            if (isChangeableOverlay(item)) {
                results.add(item);
            }
        }

        return results;
    }

    private boolean isDarkNowInternal() {
        String target = getDarkOverlayInternal();
        return isEnabled(target);
    }

    private boolean setDarkOverlayInternal(String overlayName) {
        boolean isDefault = StyleInterface.OVERLAY_DARK_DEFAULT.equals(overlayName);
        boolean isBlack = StyleInterface.OVERLAY_DARK_BLACK.equals(overlayName);

        if (!isDefault && !isBlack) {
            Log.e(TAG, overlayName + " is not a valid dark overlay!");
            return false;
        }

        return setOverlayInternal(OVERLAY_SYSTEM, overlayName);
    }

    private String getDarkOverlayInternal() {
        return getOverlayInternal(OVERLAY_SYSTEM);
    }

    /* Utils */

    private String getOverlaySetting(int overlayType) {
        switch (overlayType) {
            case OVERLAY_SYSTEM: // System
                return EVSettings.System.BERRY_DARK_OVERLAY;
            case OVERLAY_ACCENT: // Accent
            default:
                return EVSettings.System.BERRY_CURRENT_ACCENT;
        }
    }

    private boolean setOverlayInternal(int overlayType, String pkgName) {
        int userId = UserHandle.myUserId();

        // Disable current accent
        String currentOverlay = getOverlayInternal(overlayType);

        try {
            mOverlayService.setEnabled(currentOverlay, false, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to disable current accent", e);
        }

        if (overlayType != OVERLAY_SYSTEM) {
            if (StyleInterface.ACCENT_DEFAULT.equals(pkgName)) {
                return EVSettings.System.putString(mContext.getContentResolver(),
                        getOverlaySetting(overlayType), "");
            }
        }

        // Enable new one
        try {
            mOverlayService.setEnabled(pkgName, true, userId);
            return EVSettings.System.putString(mContext.getContentResolver(),
                    getOverlaySetting(overlayType), pkgName);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to enable new accent", e);
        }
        return false;
    }

    private String getOverlayInternal(int overlayType) {
        return EVSettings.System.getString(mContext.getContentResolver(),
                getOverlaySetting(overlayType));
    }

    private int getBestColor(int sourceColor, int[] colors) {
        int best = 0;
        double minDiff = Double.MAX_VALUE;

        for (int i = 0; i < colors.length; i++) {
            double diff = Math.sqrt(
                    Math.pow(Color.red(colors[i]) - Color.red(sourceColor), 2) +
                    Math.pow(Color.green(colors[i]) - Color.green(sourceColor), 2) +
                    Math.pow(Color.blue(colors[i]) - Color.blue(sourceColor), 2));

            if (diff < minDiff) {
                best = i;
                minDiff = diff;
            }
        }

        return best;
    }

    private boolean isChangeableOverlay(String pkgName) {
        if (pkgName == null) {
            return false;
        }

        if (StyleInterface.ACCENT_DEFAULT.equals(pkgName)) {
            return true;
        }

        try {
            PackageInfo pi = mPackageManager.getPackageInfo(pkgName, 0);
            return pi != null && !pi.isStaticOverlayPackage() &&
                    isValidAccent(pkgName);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean isEnabled(String pkgName) {
        int userId = UserHandle.myUserId();
        try {
            OverlayInfo info = mOverlayService.getOverlayInfo(pkgName, userId);
            return info.isEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage());
        }
        return false;
    }

    private boolean isValidAccent(String pkgName) {
        try {
            ApplicationInfo ai = mPackageManager.getApplicationInfo(pkgName,
                    PackageManager.GET_META_DATA);
            int color = ai.metaData == null ? -1 :
                    ai.metaData.getInt(ACCENT_METADATA_COLOR, -1);
            return color != -1;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean isAValidPackage(String packageName) {
        try {
            return packageName != null && mPackageManager.getPackageInfo(packageName, 0) == null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /* Binder */

    private final IBinder mService = new IStyleInterface.Stub() {
        @Override
        public boolean setGlobalStyle(int style, String packageName) {
            enforceChangeStylePermission();
            /*
             * We need to clear the caller's identity in order to
             *   allow this method call to modify settings
             *   not allowed by the caller's permissions.
             */
            long token = clearCallingIdentity();
            boolean success = setGlobalStyleInternal(style, packageName);
            restoreCallingIdentity(token);
            return success;
        }

        @Override
        public int getGlobalStyle() {
            enforceChangeStylePermission();
            /*
             * We need to clear the caller's identity in order to
             *   allow this method call to modify settings
             *   not allowed by the caller's permissions.
             */
            long token = clearCallingIdentity();
            int result = getGlobalStyleInternal();
            restoreCallingIdentity(token);
            return result;
        }

        @Override
        public boolean setAccent(String pkgName) {
            enforceChangeStylePermission();
            return setAccentInternal(pkgName);
        }

        @Override
        public String getAccent() {
            enforceChangeStylePermission();
            /*
             * We need to clear the caller's identity in order to
             *   allow this method call to modify settings
             *   not allowed by the caller's permissions.
             */
            long token = clearCallingIdentity();
            String result = getAccentInternal();
            restoreCallingIdentity(token);
            return result;
        }

        @Override
        public Suggestion getSuggestion(Bitmap source, int[] colors) {
            enforceChangeStylePermission();
            /*
             * We need to clear the caller's identity in order to
             *   allow this method call to modify settings
             *   not allowed by the caller's permissions.
             */
            long token = clearCallingIdentity();
            Suggestion result = getSuggestionInternal(source, colors);
            restoreCallingIdentity(token);
            return result;
        }

        @Override
        public List<String> getTrustedAccents() {
            enforceChangeStylePermission();
            /*
             * We need to clear the caller's identity in order to
             *   allow this method call to modify settings
             *   not allowed by the caller's permissions.
             */
            long token = clearCallingIdentity();
            List<String> result = getTrustedAccentsInternal();
            restoreCallingIdentity(token);
            return result;
        }

        @Override
        public boolean isDarkNow() {
            /*
             * We need to clear the caller's identity in order to
             *   allow this method call to modify settings
             *   not allowed by the caller's permissions.
             */
            long token = clearCallingIdentity();
            boolean result = isDarkNowInternal();
            restoreCallingIdentity(token);
            return result;
        }

        @Override
        public boolean setDarkOverlay(String overlayName) {
            enforceChangeStylePermission();
            return setDarkOverlayInternal(overlayName);
        }

        @Override
        public String getDarkOverlay() {
            /*
             * We need to clear the caller's identity in order to
             *   allow this method call to modify settings
             *   not allowed by the caller's permissions.
             */
            long token = clearCallingIdentity();
            String result = getDarkOverlayInternal();
            restoreCallingIdentity(token);
            return result;
        }
    };
}
