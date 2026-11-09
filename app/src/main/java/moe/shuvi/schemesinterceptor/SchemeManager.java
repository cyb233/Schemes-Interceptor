package moe.shuvi.schemesinterceptor;

import android.content.ComponentName;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads scheme configuration and manages the generated activity aliases. */
public final class SchemeManager {
    private static final String CONFIG_FILE = "schemes.json";
    private static final String ALIAS_PREFIX = "SchemeAlias_";
    private static final String TAG = "SchemeManager";

    private final Context appContext;
    private final PackageManager packageManager;
    private final Map<String, String> examples = new LinkedHashMap<>();

    public SchemeManager(@NonNull Context context) {
        appContext = context.getApplicationContext();
        packageManager = appContext.getPackageManager();
        Log.d(TAG, "Initialized for package=" + appContext.getPackageName());
    }

    /** A configuration item, deduplicated by scheme with descriptions joined by " / ". */
    public static final class SchemeEntry {
        private final String scheme;
        private final String description;
        private final String example;
        private final List<String> installedAppNames;
        private final List<String> installedAppPackages;
        private final List<String> unavailableAppNames;
        private final List<String> unavailableAppPackages;
        private final String defaultHandlerName;
        private final String defaultHandlerPackage;
        private final boolean enabled;

        SchemeEntry(
                String scheme,
                String description,
                String example,
                List<String> installedAppNames,
                List<String> installedAppPackages,
                List<String> unavailableAppNames,
                List<String> unavailableAppPackages,
                String defaultHandlerName,
                String defaultHandlerPackage,
                boolean enabled
        ) {
            this.scheme = scheme;
            this.description = description;
            this.example = example;
            this.installedAppNames = List.copyOf(installedAppNames);
            this.installedAppPackages = List.copyOf(installedAppPackages);
            this.unavailableAppNames = List.copyOf(unavailableAppNames);
            this.unavailableAppPackages = List.copyOf(unavailableAppPackages);
            this.defaultHandlerName = defaultHandlerName;
            this.defaultHandlerPackage = defaultHandlerPackage;
            this.enabled = enabled;
        }

        @NonNull
        public String getScheme() {
            return scheme;
        }

        @NonNull
        public String getDescription() {
            return description;
        }

        @NonNull
        public String getExample() {
            return example;
        }

        @NonNull
        public List<String> getInstalledAppNames() {
            return installedAppNames;
        }

        @NonNull
        public List<String> getInstalledAppPackages() {
            return installedAppPackages;
        }

        @NonNull
        public List<String> getUnavailableAppNames() {
            return unavailableAppNames;
        }

        @NonNull
        public List<String> getUnavailableAppPackages() {
            return unavailableAppPackages;
        }

        @NonNull
        public String getDefaultHandlerName() {
            return defaultHandlerName;
        }

        @NonNull
        public String getDefaultHandlerPackage() {
            return defaultHandlerPackage;
        }

        public boolean isEnabled() {
            return enabled;
        }

        @NonNull
        public String getDisplayScheme() {
            return scheme + "://";
        }
    }

    private record HandlerApps(
            List<String> availableNames,
            List<String> availablePackages,
            List<String> unavailableNames,
            List<String> unavailablePackages
    ) {
    }

    /**
     * Reads assets/schemes.json, merges duplicate schemes and sorts the output
     * alphabetically. The build validates descriptions and optional examples.
     */
    @NonNull
    public List<SchemeEntry> loadSchemes() throws IOException, JSONException {
        Log.i(TAG, "Loading scheme configuration from assets/" + CONFIG_FILE);
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        JSONArray source = new JSONArray(readAsset());
        examples.clear();
        Log.d(TAG, "Parsed " + source.length() + " raw configuration entries");
        Locale locale = Locale.getDefault();
        String language = locale.getLanguage();
        String region = locale.getCountry();
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.getJSONObject(index);
            String scheme = item.getString("scheme").trim();
            JSONObject translations = item.getJSONObject("desc");
            String description = region.isEmpty()
                    ? "" : translations.optString(language + "-" + region);
            if (description.isEmpty()) description = translations.optString(language);
            if (description.isEmpty()) description = translations.getString("en");
            description = description.trim();
            grouped.computeIfAbsent(scheme, ignored -> new LinkedHashSet<>()).add(description);
            if (item.has("example")) {
                examples.putIfAbsent(scheme, item.getString("example").trim());
            }
        }

        List<SchemeEntry> entries = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> item : grouped.entrySet()) {
            String scheme = item.getKey();
            HandlerApps handlerApps = findHandlerApps(scheme);
            List<String> installedAppNames = handlerApps.availableNames;
            List<String> installedAppPackages = handlerApps.availablePackages;
            ResolveInfo directHandler = installedAppNames.isEmpty() ? null : findDirectHandler(scheme);
            String defaultHandlerName = getHandlerLabel(directHandler);
            Log.v(
                    TAG,
                    "Scheme=" + scheme
                            + ", installedHandlers=" + installedAppNames.size()
                            + ", unavailableHandlers=" + handlerApps.unavailableNames.size()
                            + ", directHandler=" + (defaultHandlerName.isEmpty() ? "none" : defaultHandlerName)
            );
            entries.add(new SchemeEntry(
                    scheme,
                    join(item.getValue()),
                    examples.getOrDefault(scheme, ""),
                    installedAppNames,
                    installedAppPackages,
                    handlerApps.unavailableNames,
                    handlerApps.unavailablePackages,
                    defaultHandlerName,
                    directHandler == null ? "" : directHandler.activityInfo.packageName,
                    isAliasEnabled(scheme)
            ));
        }
        entries.sort((first, second) -> first.scheme.compareToIgnoreCase(second.scheme));
        Log.i(TAG, "Loaded " + entries.size() + " distinct schemes");
        return entries;
    }

    @NonNull
    private String getHandlerLabel(@Nullable ResolveInfo handler) {
        if (handler == null || handler.activityInfo == null) {
            return "";
        }
        return getApplicationLabel(handler.activityInfo);
    }

    @NonNull
    private String getApplicationLabel(@NonNull ActivityInfo activityInfo) {
        CharSequence label = activityInfo.applicationInfo.loadLabel(packageManager);
        return TextUtils.isEmpty(label) ? activityInfo.packageName : label.toString();
    }

    /**
     * Filters out Android's resolver activity. resolveActivity() returns that
     * system component when multiple handlers exist without a direct default.
     */
    @Nullable
    private ResolveInfo findDirectHandler(@NonNull String scheme) {
        ResolveInfo resolved = packageManager.resolveActivity(
                newSchemeIntent(scheme),
                PackageManager.MATCH_DEFAULT_ONLY
        );
        if (resolved == null || resolved.activityInfo == null
                || isOwnPackage(resolved.activityInfo.packageName)) {
            Log.d(TAG, "No resolved activity for scheme=" + scheme);
            return null;
        }
        for (ResolveInfo candidate : packageManager.queryIntentActivities(
                newSchemeIntent(scheme),
                PackageManager.MATCH_DEFAULT_ONLY
        )) {
            if (candidate.activityInfo != null
                    && resolved.activityInfo.packageName.equals(candidate.activityInfo.packageName)
                    && resolved.activityInfo.name.equals(candidate.activityInfo.name)) {
                Log.d(
                        TAG,
                        "Direct handler for scheme=" + scheme + ": "
                                + resolved.activityInfo.packageName + "/" + resolved.activityInfo.name
                );
                return resolved;
            }
        }
        Log.d(TAG, "Scheme=" + scheme + " resolves to the system resolver");
        return null;
    }

    public enum DebugResult { LAUNCHED, INVALID_URI, FILE_URI_UNSUPPORTED, NO_HANDLER, FAILED }

    /** Accepts a bare scheme or a complete URI; never bypasses Android's file sharing rules. */
    @NonNull
    public DebugResult debugLaunchScheme(@NonNull String input) {
        String value = input.trim();
        int separator = value.indexOf(':');
        String scheme = separator < 0 ? value : value.substring(0, separator);
        if (!scheme.matches("[A-Za-z][A-Za-z0-9+.-]*") || value.matches("(?s).*\\s.*")) {
            return DebugResult.INVALID_URI;
        }
        if ("file".equalsIgnoreCase(scheme)) {
            return DebugResult.FILE_URI_UNSUPPORTED;
        }
        // Only bare schemes use configured examples. Preserve complete user URIs.
        if (separator < 0 || value.equals(scheme + ":") || value.equals(scheme + "://")) {
            value = examples.getOrDefault(scheme, scheme + "://");
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(value));
            if (packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
                return DebugResult.NO_HANDLER;
            }
            intent.putExtra(Intent.EXTRA_REFERRER,
                    Uri.parse("android-app://" + appContext.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
            return DebugResult.LAUNCHED;
        } catch (ActivityNotFoundException exception) {
            // A handler can disappear between querying and starting it.
            return DebugResult.NO_HANDLER;
        } catch (RuntimeException exception) {
            // Includes permission denials and platform/OEM launch restrictions.
            Log.w(TAG, "Unable to launch debug scheme=" + scheme, exception);
            return DebugResult.FAILED;
        }
    }


    /** Opens Android's default-opening settings, falling back to app details. */
    public void openAppDefaultsSettings(@NonNull String packageName) {
        Uri packageUri = Uri.fromParts("package", packageName, null);
        Intent intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS)
                : new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(packageUri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.i(TAG, "Opening default settings for package=" + packageName);
        try {
            appContext.startActivity(intent);
        } catch (android.content.ActivityNotFoundException exception) {
            Log.w(TAG, "Default-opening settings unavailable; opening app details for package=" + packageName);
            Intent fallback = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            fallback.setData(packageUri);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(fallback);
        }
    }

    @NonNull
    private Intent newSchemeIntent(@NonNull String scheme) {
        String trimmedScheme = scheme.trim();
        int separator = trimmedScheme.indexOf(':');
        String normalizedScheme = separator >= 0
                ? trimmedScheme.substring(0, separator)
                : trimmedScheme;
        return new Intent(Intent.ACTION_VIEW, Uri.parse(normalizedScheme + "://"));
    }

    /** Returns an available/unavailable classification for external Scheme handlers. */
    @NonNull
    private HandlerApps findHandlerApps(@NonNull String scheme) {
        Intent intent = newSchemeIntent(scheme);
        Map<String, String> availableApps = new LinkedHashMap<>();
        for (ResolveInfo handler : packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
        )) {
            addExternalHandler(availableApps, handler);
        }

        Map<String, String> unavailableApps = new LinkedHashMap<>();
        for (ResolveInfo handler : packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_DISABLED_COMPONENTS
        )) {
            ActivityInfo activityInfo = handler.activityInfo;
            if (activityInfo == null
                    || availableApps.containsKey(activityInfo.packageName)
                    || isOwnPackage(activityInfo.packageName)) {
                continue;
            }
            unavailableApps.put(activityInfo.packageName, getApplicationLabel(activityInfo));
        }

        HandlerApps result = new HandlerApps(
                sortedValues(availableApps),
                sortedKeys(availableApps),
                sortedValues(unavailableApps),
                sortedKeys(unavailableApps)
        );
        Log.d(
                TAG,
                "Handler classification for scheme=" + scheme
                        + ": available=" + result.availableNames.size()
                        + ", unavailable=" + result.unavailableNames.size()
        );
        return result;
    }

    private void addExternalHandler(@NonNull Map<String, String> handlers, @NonNull ResolveInfo handler) {
        ActivityInfo activityInfo = handler.activityInfo;
        if (activityInfo != null && !isOwnPackage(activityInfo.packageName)) {
            handlers.put(activityInfo.packageName, getApplicationLabel(activityInfo));
        }
    }

    /** Both builds register the same schemes and must not count as external handlers. */
    private boolean isOwnPackage(@NonNull String packageName) {
        return appContext.getPackageName().equals(packageName)
                || BuildConfig.RELEASE_APPLICATION_ID.equals(packageName)
                || BuildConfig.DEBUG_APPLICATION_ID.equals(packageName);
    }

    @NonNull
    private static List<String> sortedKeys(@NonNull Map<String, String> apps) {
        List<String> keys = new ArrayList<>(apps.keySet());
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        return keys;
    }

    @NonNull
    private static List<String> sortedValues(@NonNull Map<String, String> apps) {
        List<String> values = new ArrayList<>(apps.values());
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    public boolean isAliasEnabled(@NonNull String scheme) {
        int state = packageManager.getComponentEnabledSetting(aliasComponent(scheme));
        boolean enabled = state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        Log.v(TAG, "Alias state: scheme=" + scheme + ", state=" + state + ", enabled=" + enabled);
        return enabled;
    }

    public void setAliasEnabled(@NonNull String scheme, boolean enabled) {
        Log.i(TAG, "Setting alias state: scheme=" + scheme + ", enabled=" + enabled);
        packageManager.setComponentEnabledSetting(
                aliasComponent(scheme),
                enabled
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
        );
    }

    @NonNull
    private ComponentName aliasComponent(@NonNull String scheme) {
        return new ComponentName(
                appContext.getPackageName(),
                appContext.getPackageName() + "." + aliasClassName(scheme)
        );
    }

    /** Must match aliasNameFor() in gradle/scheme-manifest.gradle exactly. */
    @NonNull
    private static String aliasClassName(@NonNull String scheme) {
        String safeScheme = scheme.replaceAll("[^A-Za-z0-9_]", "_");
        return ALIAS_PREFIX + safeScheme + "_" + Integer.toUnsignedString(scheme.hashCode(), 16);
    }

    @NonNull
    private String readAsset() throws IOException {
        StringBuilder result = new StringBuilder();
        try (InputStream stream = appContext.getAssets().open(CONFIG_FILE);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] characters = new char[4096];
            readInto(reader, result, characters);
        }
        return result.toString();
    }

    private static void readInto(
            @NonNull BufferedReader reader,
            @NonNull StringBuilder result,
            @NonNull char[] characters
    ) throws IOException {
        int count = reader.read(characters);
        if (count == -1) {
            return;
        }
        result.append(characters, 0, count);
        readInto(reader, result, characters);
    }

    @NonNull
    private static String join(@NonNull Set<String> items) {
        StringBuilder result = new StringBuilder();
        for (String item : items) {
            if (result.toString().isEmpty()) {
                result.append(item);
            } else {
                result.append(" / ").append(item);
            }
        }
        return result.toString();
    }
}
