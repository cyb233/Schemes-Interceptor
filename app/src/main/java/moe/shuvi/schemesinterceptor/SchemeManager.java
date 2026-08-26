package moe.shuvi.schemesinterceptor;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

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
import java.util.Collections;
import java.util.Comparator;
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

    private final Context appContext;
    private final PackageManager packageManager;

    public SchemeManager(@NonNull Context context) {
        appContext = context.getApplicationContext();
        packageManager = appContext.getPackageManager();
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
            this.installedAppNames = Collections.unmodifiableList(new ArrayList<>(installedAppNames));
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
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        JSONArray source = new JSONArray(readAsset(CONFIG_FILE));
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String scheme = item.optString("scheme", "").trim();
            if (scheme.isEmpty()) {
                continue;
            }
            String description = resolveDescription(item.opt("desc"));
            LinkedHashSet<String> descriptions = grouped.get(scheme);
            if (descriptions == null) {
                descriptions = new LinkedHashSet<>();
                grouped.put(scheme, descriptions);
            }
            if (!description.isEmpty()) {
                descriptions.add(description);
            }
        }

        List<SchemeEntry> entries = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> item : grouped.entrySet()) {
            String scheme = item.getKey();
            List<String> installedAppNames = findInstalledAppNames(scheme);
            ResolveInfo directHandler = installedAppNames.isEmpty() ? null : findDirectHandler(scheme);
            String defaultHandlerName = getHandlerLabel(directHandler);
            entries.add(new SchemeEntry(
                    scheme,
                    join(item.getValue(), " / "),
                    installedAppNames,
                    defaultHandlerName,
                    directHandler == null ? "" : directHandler.activityInfo.packageName,
                    isAliasEnabled(scheme)
            ));
        }
        Collections.sort(entries, new Comparator<SchemeEntry>() {
            @Override
            public int compare(SchemeEntry first, SchemeEntry second) {
                return first.scheme.compareToIgnoreCase(second.scheme);
            }
        });
        return entries;
    }

    /**
     * Resolves a description from either a literal string or a localized object.
     * Objects first use the full Android locale tag, then the language code, and
     * finally their mandatory English fallback.
     */
    @NonNull
    private static String resolveDescription(Object rawDescription) {
        if (rawDescription instanceof String) {
            return ((String) rawDescription).trim();
        }
        if (!(rawDescription instanceof JSONObject)) {
            return "";
        }

        JSONObject translations = (JSONObject) rawDescription;
        Locale locale = Locale.getDefault();
        String languageTag = locale.toLanguageTag();
        String description = translations.optString(languageTag, "").trim();
        if (!description.isEmpty()) {
            return description;
        }

        // Android uses BCP 47 tags (for example zh-Hans-CN). Also accept
        // resource-style locale keys such as zh-CN in configuration files.
        String resourceStyleTag = languageTag.replace('-', '_');
        description = translations.optString(resourceStyleTag, "").trim();
        if (!description.isEmpty()) {
            return description;
        }
        description = translations.optString(locale.getLanguage(), "").trim();
        if (!description.isEmpty()) {
            return description;
        }
        return translations.optString("en", "").trim();
    }

    /**
     * Returns the application Android will launch directly for this Scheme.
     * Returns an empty string when Android would show its resolver instead.
     */
    @NonNull
    public String findDefaultHandlerName(@NonNull String scheme) {
        ResolveInfo handler = findDirectHandler(scheme);
        return getHandlerLabel(handler);
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
        return label == null || label.length() == 0
                ? activityInfo.packageName
                : label.toString();
    }

    /** Returns whether at least one enabled activity can handle a Scheme. */
    public boolean hasHandler(@NonNull String scheme) {
        return !packageManager.queryIntentActivities(
                newSchemeIntent(scheme),
                PackageManager.MATCH_DEFAULT_ONLY
        ).isEmpty();
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
            return null;
        }
        for (ResolveInfo candidate : packageManager.queryIntentActivities(
                newSchemeIntent(scheme),
                PackageManager.MATCH_DEFAULT_ONLY
        )) {
            if (candidate.activityInfo != null
                    && resolved.activityInfo.packageName.equals(candidate.activityInfo.packageName)
                    && resolved.activityInfo.name.equals(candidate.activityInfo.name)) {
                return resolved;
            }
        }
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
        appContext.startActivity(intent);
    }


    /** Opens Android's default-opening settings, falling back to app details. */
    public void openAppDefaultsSettings(@NonNull String packageName) {
        Uri packageUri = Uri.fromParts("package", packageName, null);
        Intent intent = new Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS);
        intent.setData(packageUri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            appContext.startActivity(intent);
        } catch (android.content.ActivityNotFoundException ignored) {
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
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    public boolean isAliasEnabled(@NonNull String scheme) {
        int state = packageManager.getComponentEnabledSetting(aliasComponent(scheme));
        return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    public void setAliasEnabled(@NonNull String scheme, boolean enabled) {
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
    private String readAsset(@NonNull String name) throws IOException {
        StringBuilder result = new StringBuilder();
        try (InputStream stream = appContext.getAssets().open(name);
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
    private static String join(@NonNull Set<String> items, @NonNull String separator) {
        StringBuilder result = new StringBuilder();
        for (String item : items) {
            if (result.length() > 0) {
                result.append(separator);
            }
            result.append(item);
        }
        return result.toString();
    }
}
