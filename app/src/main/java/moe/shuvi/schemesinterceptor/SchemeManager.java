package moe.shuvi.schemesinterceptor;

import android.content.ComponentName;
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
import java.util.stream.IntStream;

/** Loads scheme configuration and manages the generated activity aliases. */
public final class SchemeManager {
    private static final String CONFIG_FILE = "schemes.json";
    private static final String ALIAS_PREFIX = "SchemeAlias_";
    private static final String TAG = "SchemeManager";

    private final Context appContext;
    private final PackageManager packageManager;

    public SchemeManager(@NonNull Context context) {
        appContext = context.getApplicationContext();
        packageManager = appContext.getPackageManager();
        Log.d(TAG, "Initialized for package=" + appContext.getPackageName());
    }

    /** A configuration item, deduplicated by scheme with descriptions joined by " / ". */
    public static final class SchemeEntry {
        private final String scheme;
        private final String description;
        private final List<String> installedAppNames;
        private final String defaultHandlerName;
        private final String defaultHandlerPackage;
        private final boolean enabled;

        SchemeEntry(
                String scheme,
                String description,
                List<String> installedAppNames,
                String defaultHandlerName,
                String defaultHandlerPackage,
                boolean enabled
        ) {
            this.scheme = scheme;
            this.description = description;
            this.installedAppNames = List.copyOf(installedAppNames);
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
        public List<String> getInstalledAppNames() {
            return installedAppNames;
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

    /**
     * Reads assets/schemes.json, merges duplicate schemes and sorts the output
     * alphabetically. Invalid or blank scheme records are ignored.
     */
    @NonNull
    public List<SchemeEntry> loadSchemes() throws IOException, JSONException {
        Log.i(TAG, "Loading scheme configuration from assets/" + CONFIG_FILE);
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        JSONArray source = new JSONArray(readAsset());
        Log.d(TAG, "Parsed " + source.length() + " raw configuration entries");
        IntStream.range(0, source.length()).forEach(index -> {
            JSONObject item = source.optJSONObject(index);
            if (item == null) {
                Log.w(TAG, "Ignoring non-object configuration entry at index=" + index);
                return;
            }
            String scheme = item.optString("scheme", "").trim();
            if (scheme.isEmpty()) {
                Log.w(TAG, "Ignoring configuration entry with empty scheme at index=" + index);
                return;
            }
            String description = resolveDescription(item.opt("desc"));
            LinkedHashSet<String> descriptions = grouped.computeIfAbsent(
                    scheme,
                    ignored -> new LinkedHashSet<>()
            );
            if (!description.isEmpty()) {
                descriptions.add(description);
            }
        });

        List<SchemeEntry> entries = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> item : grouped.entrySet()) {
            String scheme = item.getKey();
            List<String> installedAppNames = findInstalledAppNames(scheme);
            ResolveInfo directHandler = installedAppNames.isEmpty() ? null : findDirectHandler(scheme);
            String defaultHandlerName = getHandlerLabel(directHandler);
            Log.v(
                    TAG,
                    "Scheme=" + scheme
                            + ", installedHandlers=" + installedAppNames.size()
                            + ", directHandler=" + (defaultHandlerName.isEmpty() ? "none" : defaultHandlerName)
            );
            entries.add(new SchemeEntry(
                    scheme,
                    join(item.getValue()),
                    installedAppNames,
                    defaultHandlerName,
                    directHandler == null ? "" : directHandler.activityInfo.packageName,
                    isAliasEnabled(scheme)
            ));
        }
        entries.sort((first, second) -> first.scheme.compareToIgnoreCase(second.scheme));
        Log.i(TAG, "Loaded " + entries.size() + " distinct schemes");
        return entries;
    }

    /**
     * Resolves a description from either a literal string or a localized object.
     * Objects first use the full Android locale tag, then language-country
     * combinations (for example zh-CN), the language code, and finally English.
     */
    @NonNull
    private static String resolveDescription(Object rawDescription) {
        if (rawDescription instanceof String) {
            return ((String) rawDescription).trim();
        }
        if (!(rawDescription instanceof JSONObject translations)) {
            return "";
        }
        Locale locale = Locale.getDefault();
        String languageTag = locale.toLanguageTag();
        String matchingDescription = translations.optString(languageTag, "").trim();
        if (!matchingDescription.isEmpty()) {
            return matchingDescription;
        }

        // Android may report a script-qualified tag (for example zh-Hans-CN),
        // while the asset uses conventional language-country keys (zh-CN).
        String language = locale.getLanguage();
        String country = locale.getCountry();
        if (!language.isEmpty() && !country.isEmpty()) {
            String languageCountryDescription = translations.optString(
                    language + "-" + country,
                    ""
            ).trim();
            if (!languageCountryDescription.isEmpty()) {
                return languageCountryDescription;
            }
        }

        String resourceStyleTag = languageTag.replace('-', '_');
        String resourceStyleDescription = translations.optString(resourceStyleTag, "").trim();
        if (!resourceStyleDescription.isEmpty()) {
            return resourceStyleDescription;
        }
        String languageDescription = translations.optString(language, "").trim();
        if (!languageDescription.isEmpty()) {
            return languageDescription;
        }
        return translations.optString("en", "").trim();
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

    /** Returns whether at least one enabled activity can handle a Scheme. */
    public boolean hasHandler(@NonNull String scheme) {
        int handlerCount = packageManager.queryIntentActivities(
                newSchemeIntent(scheme),
                PackageManager.MATCH_DEFAULT_ONLY
        ).size();
        Log.d(TAG, "Handler availability for scheme=" + scheme + ": count=" + handlerCount);
        return handlerCount > 0;
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
        if (resolved == null || resolved.activityInfo == null) {
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

    /** Launches a resolver-visible Scheme intent for debugging. */
    public void debugLaunchScheme(@NonNull String scheme) {
        Intent intent = newSchemeIntent(scheme);
        intent.putExtra(
                Intent.EXTRA_REFERRER,
                Uri.parse("android-app://" + appContext.getPackageName())
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.i(TAG, "Launching debug intent for scheme=" + scheme);
        try {
            appContext.startActivity(intent);
        } catch (android.content.ActivityNotFoundException exception) {
            Log.w(TAG, "No activity found while launching debug scheme=" + scheme, exception);
            throw exception;
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
        String normalized = scheme.trim();
        int separator = normalized.indexOf(':');
        if (separator >= 0) {
            normalized = normalized.substring(0, separator);
        }
        return new Intent(Intent.ACTION_VIEW, Uri.parse(normalized + "://"));
    }

    /** Returns distinct labels of other installed apps that can handle this scheme. */
    @NonNull
    public List<String> findInstalledAppNames(@NonNull String scheme) {
        List<ResolveInfo> handlers = packageManager.queryIntentActivities(
                newSchemeIntent(scheme),
                PackageManager.MATCH_DEFAULT_ONLY
        );
        Set<String> appNames = new LinkedHashSet<>();
        for (ResolveInfo handler : handlers) {
            ActivityInfo activityInfo = handler.activityInfo;
            if (activityInfo == null || appContext.getPackageName().equals(activityInfo.packageName)) {
                continue;
            }
            appNames.add(getApplicationLabel(activityInfo));
        }
        List<String> result = new ArrayList<>(appNames);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        Log.d(TAG, "Found " + result.size() + " external handler apps for scheme=" + scheme);
        return result;
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
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                result.append(buffer, 0, count);
            }
        }
        return result.toString();
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
