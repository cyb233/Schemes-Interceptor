package moe.shuvi.schemesinterceptor;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SettingsActivity extends AppCompatActivity {
    private SchemeManager schemeManager;
    private SchemeAdapter adapter;
    private final List<SchemeManager.SchemeEntry> allEntries = new ArrayList<>();
    private EditText searchInput;
    private TextView emptyView;
    private boolean installedOnly;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        searchInput = findViewById(R.id.search_input);
        emptyView = findViewById(R.id.empty_view);
        schemeManager = new SchemeManager(this);
        adapter = new SchemeAdapter((entry, enabled) -> {
            schemeManager.setAliasEnabled(entry.getScheme(), enabled);
            reloadEntries();
        });

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
        reloadEntries();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
            item.setChecked(installedOnly);
            applyFilters();
            return true;
        }
        if (id == R.id.action_about) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.about)
                    .setMessage(R.string.about_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("installedOnly", installedOnly);
    }

    private void reloadEntries() {
        try {
            allEntries.clear();
            allEntries.addAll(schemeManager.loadSchemes());
            applyFilters();
        } catch (IOException | JSONException exception) {
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
            if (!query.isEmpty()
                    && !entry.getScheme().toLowerCase(Locale.ROOT).contains(query)
                    && !entry.getDescription().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            filtered.add(entry);
        }
        adapter.submitList(filtered);
        emptyView.setText(R.string.no_schemes);
        emptyView.setVisibility(filtered.isEmpty()
                ? android.view.View.VISIBLE
                : android.view.View.GONE);
    }

    /** Reduces the TextWatcher implementation to the only callback this screen needs. */
    private abstract static class SimpleTextWatcher implements android.text.TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
        }
    }
}
