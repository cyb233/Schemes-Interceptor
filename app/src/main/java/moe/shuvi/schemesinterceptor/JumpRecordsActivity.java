package moe.shuvi.schemesinterceptor;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public final class JumpRecordsActivity extends AppCompatActivity {
    private JumpRecordStore store;
    private TextView emptyView;
    private TextView accessibilityButton;
    private EditText searchInput;
    private boolean loading;
    private boolean hasMore = true;
    private int loadVersion;
    private boolean accessibilityEnabled;
    private final RecordsAdapter adapter = new RecordsAdapter();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_jump_records);
        View root = findViewById(R.id.records_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        Toolbar toolbar = findViewById(R.id.records_toolbar);
        toolbar.setTitle(R.string.jump_records);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        findViewById(R.id.records_help).setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.recording_details)
                .setMessage(R.string.jump_records_explanation)
                .setPositiveButton(android.R.string.ok, null)
                .show());

        store = JumpRecordStore.get(this);
        searchInput = findViewById(R.id.records_search);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { refreshRecords(); }
        });
        accessibilityButton = findViewById(R.id.accessibility_settings);
        accessibilityButton.setOnClickListener(view -> {
            if (accessibilityEnabled) {
                openAccessibilitySettings();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.window_recording)
                        .setMessage(R.string.window_recording_description)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setNeutralButton(R.string.open_app_info,
                                (dialog, which) -> openAppInfo())
                        .setPositiveButton(R.string.open_settings,
                                (dialog, which) -> openAccessibilitySettings())
                        .show();
            }
        });
        emptyView = findViewById(R.id.records_empty);
        RecyclerView list = findViewById(R.id.records_list);
        LinearLayoutManager layout = new LinearLayoutManager(this);
        list.setLayoutManager(layout);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && layout.findLastVisibleItemPosition() >= adapter.getItemCount() - 5) {
                    loadNextPage();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        accessibilityEnabled = false;
        if (manager != null) {
            for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                var service = info.getResolveInfo().serviceInfo;
                if (getPackageName().equals(service.packageName)
                        && WindowTransitionService.class.getName().equals(service.name)) {
                    accessibilityEnabled = true;
                    break;
                }
            }
        }
        accessibilityButton.setText(accessibilityEnabled
                ? R.string.window_recording_on : R.string.window_recording_off);
        refreshRecords();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.jump_records_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == R.id.action_clear_records) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.clear_records_confirmation)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.clear_records, (dialog, which) -> store.clear(success -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (success) refreshRecords();
                        else Toast.makeText(this, R.string.records_error, Toast.LENGTH_SHORT).show();
                    }))
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException exception) {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppInfo() {
        try {
            startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
            ));
        } catch (ActivityNotFoundException | SecurityException exception) {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshRecords() {
        loadVersion++;
        loading = false;
        hasMore = true;
        adapter.submit(Collections.emptyList());
        loadNextPage();
    }

    private void loadNextPage() {
        if (loading || !hasMore) return;
        loading = true;
        int version = loadVersion;
        String query = searchInput.getText().toString().trim();
        store.load(query, adapter.getItemCount(), entries -> {
            if (isFinishing() || isDestroyed() || version != loadVersion) return;
            loading = false;
            if (entries != null) {
                adapter.append(entries);
                hasMore = entries.size() == JumpRecordStore.PAGE_SIZE;
            } else {
                hasMore = false;
            }
            emptyView.setText(entries == null ? R.string.records_error
                    : query.isEmpty() ? R.string.no_records : R.string.no_matching_records);
            emptyView.setVisibility(adapter.entries.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private void showRecord(JumpRecordStore.Entry entry) {
        String text = formatEntry(entry, false);
        View content = getLayoutInflater().inflate(R.layout.dialog_jump_record, null);
        SwitchCompat decodeSwitch = content.findViewById(R.id.record_decode_switch);
        TextView message = content.findViewById(R.id.record_message);
        ScrollView scroll = content.findViewById(R.id.record_scroll);
        message.setText(text);
        decodeSwitch.setVisibility(entry.type() == JumpRecordStore.INTERCEPTED
                ? View.VISIBLE : View.GONE);

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        float density = getResources().getDisplayMetrics().density;
        message.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1,
                screenWidth - Math.round(96 * density)),
                View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        scroll.getLayoutParams().height = Math.min(message.getMeasuredHeight(),
                Math.min(Math.round(420 * density), (int) (screenHeight * 0.42f)));
        decodeSwitch.setOnCheckedChangeListener((button, decoded) -> {
            message.setText(formatEntry(entry, decoded));
            scroll.scrollTo(0, 0);
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.jump_records)
                .setView(content)
                .setNeutralButton(entry.type() == JumpRecordStore.INTERCEPTED
                        ? R.string.copy_original_uri : R.string.copy_record, (ignored, which) -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.jump_records),
                                entry.type() == JumpRecordStore.INTERCEPTED ? entry.data() : text));
                    }
                })
                .setNegativeButton(R.string.delete_record, (ignored, which) ->
                        new AlertDialog.Builder(this)
                                .setMessage(R.string.delete_record_confirmation)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.delete_record, (confirmation, button) ->
                                        store.delete(entry.id(), success -> {
                                            if (isFinishing() || isDestroyed()) return;
                                            if (success) refreshRecords();
                                            else Toast.makeText(this, R.string.records_error,
                                                    Toast.LENGTH_SHORT).show();
                                        }))
                                .show())
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();
    }

    private String formatEntry(JumpRecordStore.Entry entry, boolean decoded) {
        String source = entry.source().isEmpty() ? getString(R.string.unknown_caller) : entry.source();
        String data = decoded && entry.type() == JumpRecordStore.INTERCEPTED
                ? Uri.decode(entry.data()) : entry.data();
        return DateFormat.getDateTimeInstance().format(new Date(entry.time())) + " · "
                + getString(entry.type() == JumpRecordStore.INTERCEPTED
                        ? R.string.record_intercepted_type : R.string.record_window_type)
                + "\n" + source + " → " + entry.target() + "\n" + data;
    }

    private final class RecordsAdapter extends RecyclerView.Adapter<RecordHolder> {
        private final List<JumpRecordStore.Entry> entries = new ArrayList<>();

        void submit(List<JumpRecordStore.Entry> updated) {
            int oldSize = entries.size();
            entries.clear();
            if (oldSize > 0) notifyItemRangeRemoved(0, oldSize);
            entries.addAll(updated);
            if (!entries.isEmpty()) notifyItemRangeInserted(0, entries.size());
        }

        void append(List<JumpRecordStore.Entry> page) {
            if (page.isEmpty()) return;
            int start = entries.size();
            entries.addAll(page);
            notifyItemRangeInserted(start, page.size());
        }

        @NonNull
        @Override
        public RecordHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecordHolder(getLayoutInflater().inflate(R.layout.item_jump_record, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecordHolder holder, int position) {
            JumpRecordStore.Entry entry = entries.get(position);
            holder.text.setText(formatEntry(entry, false));
            holder.text.setOnClickListener(view -> showRecord(entry));
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }

    private static final class RecordHolder extends RecyclerView.ViewHolder {
        final TextView text;

        RecordHolder(View view) {
            super(view);
            text = (TextView) view;
        }
    }
}
