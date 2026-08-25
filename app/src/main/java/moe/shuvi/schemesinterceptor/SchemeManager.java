package moe.shuvi.schemesinterceptor;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import androidx.annotation.NonNull;

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
        private final boolean enabled;

        SchemeEntry(String scheme, String description, List<String> installedAppNames, boolean enabled) {
            this.scheme = scheme;
            this.description = description;
            this.installedAppNames = Collections.unmodifiableList(new ArrayList<>(installedAppNames));
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
            entries.add(new SchemeEntry(
                    scheme,
                    join(item.getValue(), " / "),
                    findInstalledAppNames(scheme),
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

    @NonNull
    public List<String> findInstalledAppNames(@NonNull String scheme) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(scheme + "://"));
        List<ResolveInfo> handlers = packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        Set<String> appNames = new LinkedHashSet<>();
        for (ResolveInfo handler : handlers) {
            ActivityInfo activityInfo = handler.activityInfo;
            if (activityInfo == null || appContext.getPackageName().equals(activityInfo.packageName)) {
                continue;
            }
            CharSequence label = activityInfo.loadLabel(packageManager);
            if (label != null && label.length() > 0) {
                appNames.add(label.toString());
            } else {
                appNames.add(activityInfo.packageName);
            }
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
