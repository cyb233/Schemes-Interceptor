package moe.shuvi.schemesinterceptor;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/cyb233/Schemes-Interceptor/releases/latest";
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SchemeManager schemeManager;
    private SchemeAdapter adapter;
    private final List<SchemeManager.SchemeEntry> allEntries = new ArrayList<>();
    private EditText searchInput;
    private EditText debugSchemeInput;
    private TextView emptyView;
    private boolean installedOnly;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate; restoringState=" + (savedInstanceState != null));
        setContentView(R.layout.activity_settings);

        View appBarContainer = findViewById(R.id.app_bar_container);
        ViewCompat.setOnApplyWindowInsetsListener(appBarContainer, (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(
                    view.getPaddingLeft(),
                    systemBars.top,
                    view.getPaddingRight(),
                    view.getPaddingBottom()
            );
            return insets;
        });
        ViewCompat.requestApplyInsets(appBarContainer);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        schemeManager = new SchemeManager(this);
        searchInput = findViewById(R.id.search_input);
        debugSchemeInput = findViewById(R.id.debug_scheme_input);
        findViewById(R.id.debug_scheme_button).setOnClickListener(view ->
                debugLaunchScheme(debugSchemeInput.getText().toString())
        );
        emptyView = findViewById(R.id.empty_view);
        adapter = new SchemeAdapter(
                (entry, enabled) -> {
                    Log.i(TAG, "Scheme alias changed: scheme=" + entry.getScheme() + ", enabled=" + enabled);
                    schemeManager.setAliasEnabled(entry.getScheme(), enabled);
                    reloadEntries();
                },
                entry -> {
                    Log.i(
                            TAG,
                            "Opening default settings: scheme=" + entry.getScheme()
                                    + ", package=" + entry.getDefaultHandlerPackage()
                    );
                    schemeManager.openAppDefaultsSettings(entry.getDefaultHandlerPackage());
                },
                this::showSchemeDetails,
                this::fillDebugScheme
        );

        RecyclerView schemeList = findViewById(R.id.scheme_list);
        schemeList.setLayoutManager(new LinearLayoutManager(this));
        schemeList.setAdapter(adapter);

        searchInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable editable) {
                applyFilters();
            }
        });

        installedOnly = savedInstanceState != null && savedInstanceState.getBoolean("installedOnly", false);
        Log.d(TAG, "Initial installedOnly=" + installedOnly);
        reloadEntries();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume; refreshing scheme state");
        // Package installation/removal and state changes made from Android
        // settings are reflected whenever this screen becomes visible.
        if (schemeManager != null) {
            reloadEntries();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.settings_menu, menu);
        MenuItem installedItem = menu.findItem(R.id.action_installed_only);
        installedItem.setChecked(installedOnly);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_installed_only).setChecked(installedOnly);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_installed_only) {
            installedOnly = !installedOnly;
            Log.i(TAG, "Installed-only filter changed to " + installedOnly);
            item.setChecked(installedOnly);
            applyFilters();
            return true;
        }
        if (id == R.id.action_check_update) {
            checkForUpdate();
            return true;
        }
        if (id == R.id.action_about) {
            Log.d(TAG, "Showing about dialog");
            SpannableString message = new SpannableString(getString(R.string.about_message));
            Linkify.addLinks(message, Linkify.WEB_URLS);
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.about)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
            dialog.setOnShowListener(ignored -> {
                TextView messageView = dialog.findViewById(android.R.id.message);
                if (messageView != null) {
                    messageView.setMovementMethod(LinkMovementMethod.getInstance());
                }
            });
            dialog.show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("installedOnly", installedOnly);
    }

    private void fillDebugScheme(@NonNull SchemeManager.SchemeEntry entry) {
        Log.d(TAG, "Filling debug field with scheme=" + entry.getScheme());
        debugSchemeInput.setText(entry.getScheme());
        debugSchemeInput.setSelection(debugSchemeInput.length());
        debugSchemeInput.requestFocus();
        Toast.makeText(this, getString(R.string.debug_scheme_filled, entry.getDisplayScheme()), Toast.LENGTH_SHORT)
                .show();
    }

    private void showSchemeDetails(@NonNull SchemeManager.SchemeEntry entry) {
        Log.d(TAG, "Showing details for scheme=" + entry.getScheme());
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(
                R.layout.bottom_sheet_scheme_detail,
                findViewById(android.R.id.content),
                false
        );
        ((TextView) content.findViewById(R.id.scheme_detail_scheme)).setText(entry.getDisplayScheme());

        TextView description = content.findViewById(R.id.scheme_detail_description);
        description.setText(getString(R.string.scheme_detail_description, entry.getDescription()));

        TextView handler = content.findViewById(R.id.scheme_detail_handler);
        if (entry.getDefaultHandlerName().isEmpty()) {
            handler.setVisibility(View.GONE);
        } else {
            handler.setText(getString(R.string.current_handler, entry.getDefaultHandlerName()));
        }

        TextView installedApps = content.findViewById(R.id.scheme_detail_installed_apps);
        if (entry.getInstalledAppNames().isEmpty()) {
            installedApps.setText(R.string.no_installed_apps);
        } else {
            installedApps.setText(getString(
                    R.string.installed_apps,
                    join(entry.getInstalledAppNames())
            ));
        }
        TextView unavailableApps = content.findViewById(R.id.scheme_detail_unavailable_apps);
        if (entry.getUnavailableAppNames().isEmpty()) {
            unavailableApps.setVisibility(View.GONE);
        } else {
            unavailableApps.setText(getString(
                    R.string.unavailable_apps_detail,
                    join(entry.getUnavailableAppNames())
            ));
        }

        dialog.setContentView(content);
        dialog.show();
    }

    @NonNull
    private static String join(@NonNull List<String> items) {
        return TextUtils.join(" / ", items);
    }

    private void debugLaunchScheme(@NonNull String input) {
        String requestedScheme = input.trim();
        Log.d(TAG, "Debug launch requested with input=" + requestedScheme);
        if (requestedScheme.isEmpty()) {
            Log.w(TAG, "Debug launch rejected: empty Scheme");
            Toast.makeText(this, R.string.debug_scheme_required, Toast.LENGTH_SHORT).show();
            return;
        }
        int separator = requestedScheme.indexOf(':');
        String scheme = separator >= 0
                ? requestedScheme.substring(0, separator)
                : requestedScheme;
        if (!scheme.matches("[A-Za-z][A-Za-z0-9+.-]*")) {
            Log.w(TAG, "Debug launch rejected: invalid Scheme=" + scheme);
            Toast.makeText(this, R.string.debug_scheme_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!schemeManager.hasHandler(scheme)) {
            Log.i(TAG, "Debug launch has no external handler for scheme=" + scheme);
            Toast.makeText(this, getString(R.string.debug_scheme_no_handler, scheme), Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        Log.i(TAG, "Starting debug intent for scheme=" + scheme);
        schemeManager.debugLaunchScheme(scheme);
    }

    private void reloadEntries() {
        Log.d(TAG, "Reloading scheme entries");
        try {
            allEntries.clear();
            allEntries.addAll(schemeManager.loadSchemes());
            Log.i(TAG, "Reloaded " + allEntries.size() + " scheme entries");
            applyFilters();
        } catch (IOException | JSONException exception) {
            Log.e(TAG, "Failed to load scheme configuration", exception);
            allEntries.clear();
            adapter.submitList(allEntries);
            emptyView.setText(exception.getMessage());
            emptyView.setVisibility(android.view.View.VISIBLE);
        }
    }

    private void applyFilters() {
        String query = searchInput == null
                ? ""
                : searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<SchemeManager.SchemeEntry> filtered = new ArrayList<>();
        for (SchemeManager.SchemeEntry entry : allEntries) {
            if (installedOnly && entry.getInstalledAppNames().isEmpty()) {
                continue;
            }
            if (!query.isEmpty() && !matchesQuery(entry, query)) {
                continue;
            }
            filtered.add(entry);
        }
        adapter.submitList(filtered);
        Log.d(
                TAG,
                "Applied filters: query=" + (query.isEmpty() ? "<empty>" : query)
                        + ", installedOnly=" + installedOnly
                        + ", displayed=" + filtered.size() + "/" + allEntries.size()
        );
        emptyView.setText(R.string.no_schemes);
        emptyView.setVisibility(filtered.isEmpty()
                ? android.view.View.VISIBLE
                : android.view.View.GONE);
    }

    private static boolean matchesQuery(@NonNull SchemeManager.SchemeEntry entry, @NonNull String query) {
        return containsIgnoreCase(entry.getScheme(), query)
                || containsIgnoreCase(entry.getDescription(), query)
                || containsAnyIgnoreCase(entry.getInstalledAppNames(), query)
                || containsAnyIgnoreCase(entry.getInstalledAppPackages(), query)
                || containsAnyIgnoreCase(entry.getUnavailableAppNames(), query)
                || containsAnyIgnoreCase(entry.getUnavailableAppPackages(), query);
    }

    private static boolean containsAnyIgnoreCase(@NonNull List<String> values, @NonNull String query) {
        for (String value : values) {
            if (containsIgnoreCase(value, query)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(@NonNull String value, @NonNull String query) {
        return value.toLowerCase(Locale.ROOT).contains(query);
    }


    private void checkForUpdate() {
        Toast.makeText(this, R.string.checking_for_update, Toast.LENGTH_SHORT).show();
        updateExecutor.execute(() -> {
            try {
                ReleaseInfo release = fetchLatestRelease();
                mainHandler.post(() -> showUpdateResult(release));
            } catch (IOException | JSONException exception) {
                Log.e(TAG, "Failed to check for updates", exception);
                mainHandler.post(() -> Toast.makeText(
                        this,
                        R.string.update_check_failed,
                        Toast.LENGTH_SHORT
                ).show());
            }
        });
    }

    @NonNull
    private ReleaseInfo fetchLatestRelease() throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_URL).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", getPackageName());
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub API returned " + connection.getResponseCode());
            }
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            JSONObject json = new JSONObject(response.toString());
            return new ReleaseInfo(
                    json.optString("tag_name"),
                    json.optString("html_url"),
                    json.optString("body")
            );
        } finally {
            connection.disconnect();
        }
    }

    private void showUpdateResult(@NonNull ReleaseInfo release) {
        if (!isNewerVersion(release.tagName)) {
            Toast.makeText(this, R.string.already_latest_version, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_available_title, release.tagName))
                .setMessage(getString(R.string.update_available_message, release.releaseNotes))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.download, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl)));
                    } catch (Exception exception) {
                        Log.e(TAG, "Unable to open release page", exception);
                        Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private boolean isNewerVersion(@NonNull String releaseTag) {
        String normalizedTag = releaseTag.startsWith("v") || releaseTag.startsWith("V")
                ? releaseTag.substring(1)
                : releaseTag;
        String[] remoteParts = normalizedTag.split("\\.");
        String[] localParts = getLocalVersionName().split("\\.");
        try {
            int length = Math.max(remoteParts.length, localParts.length);
            for (int index = 0; index < length; index++) {
                int remote = index < remoteParts.length ? Integer.parseInt(remoteParts[index]) : 0;
                int local = index < localParts.length ? Integer.parseInt(localParts[index]) : 0;
                if (remote != local) {
                    return remote > local;
                }
            }
        } catch (NumberFormatException exception) {
            Log.w(TAG, "Unable to compare release version: " + releaseTag, exception);
        }
        return false;
    }

    @NonNull
    private String getLocalVersionName() {
        try {
            String versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
            return versionName == null ? "0" : versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException exception) {
            Log.w(TAG, "Unable to read local version", exception);
            return "0";
        }
    }

    @Override
    protected void onDestroy() {
        updateExecutor.shutdownNow();
        super.onDestroy();
    }

    private static final class ReleaseInfo {
        @NonNull
        final String tagName;
        @NonNull
        final String releaseUrl;
        @NonNull
        final String releaseNotes;

        ReleaseInfo(@NonNull String tagName, @NonNull String releaseUrl, @NonNull String releaseNotes) {
            this.tagName = tagName;
            this.releaseUrl = releaseUrl;
            this.releaseNotes = releaseNotes;
        }
    }

    private abstract static class SimpleTextWatcher implements android.text.TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
        }
    }
}
