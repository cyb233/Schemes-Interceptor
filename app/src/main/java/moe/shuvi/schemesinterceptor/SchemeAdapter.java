package moe.shuvi.schemesinterceptor;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public final class SchemeAdapter extends RecyclerView.Adapter<SchemeAdapter.ViewHolder> {
    private static final String TAG = "SchemeAdapter";
    public interface OnEnabledChangedListener {
        void onEnabledChanged(@NonNull SchemeManager.SchemeEntry entry, boolean enabled);
    }

    public interface OnClearDefaultListener {
        void onClearDefault(@NonNull SchemeManager.SchemeEntry entry);
    }

    public interface OnEntryClickListener {
        void onEntryClick(@NonNull SchemeManager.SchemeEntry entry);
    }

    public interface OnEntryLongClickListener {
        void onEntryLongClick(@NonNull SchemeManager.SchemeEntry entry);
    }

    private final List<SchemeManager.SchemeEntry> entries = new ArrayList<>();
    private final OnEnabledChangedListener enabledChangedListener;
    private final OnClearDefaultListener clearDefaultListener;
    private final OnEntryClickListener entryClickListener;
    private final OnEntryLongClickListener entryLongClickListener;

    public SchemeAdapter(
            @NonNull OnEnabledChangedListener enabledChangedListener,
            @NonNull OnClearDefaultListener clearDefaultListener,
            @NonNull OnEntryClickListener entryClickListener,
            @NonNull OnEntryLongClickListener entryLongClickListener
    ) {
        this.enabledChangedListener = enabledChangedListener;
        this.clearDefaultListener = clearDefaultListener;
        this.entryClickListener = entryClickListener;
        this.entryLongClickListener = entryLongClickListener;
        setHasStableIds(true);
    }

    public void submitList(@NonNull List<SchemeManager.SchemeEntry> newEntries) {
        Log.d(TAG, "Submitting " + newEntries.size() + " scheme entries");
        int previousSize = entries.size();
        entries.clear();
        if (previousSize > 0) {
            notifyItemRangeRemoved(0, previousSize);
        }
        entries.addAll(newEntries);
        if (!newEntries.isEmpty()) {
            notifyItemRangeInserted(0, newEntries.size());
        }
    }

    @Override
    public long getItemId(int position) {
        return entries.get(position).getScheme().hashCode();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_scheme, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SchemeManager.SchemeEntry entry = entries.get(position);
        holder.title.setText(entry.getDisplayScheme());
        String subtitle = buildSubtitle(holder.itemView.getContext(), entry);
        holder.subtitle.setText(subtitle);
        holder.subtitle.setVisibility(subtitle.isEmpty()
                ? View.GONE
                : View.VISIBLE);

        holder.clearDefault.setVisibility(entry.getDefaultHandlerPackage().isEmpty()
                ? View.GONE
                : View.VISIBLE);
        holder.clearDefault.setOnClickListener(entry.getDefaultHandlerPackage().isEmpty()
                ? null
                : view -> {
                    Log.i(
                            TAG,
                            "Default-settings requested for scheme=" + entry.getScheme()
                                    + ", package=" + entry.getDefaultHandlerPackage()
                    );
                    clearDefaultListener.onClearDefault(entry);
                });

        // Detach before changing checked state: RecyclerView reuses views and
        // setChecked() would otherwise persist an unintended component state.
        holder.enabled.setOnCheckedChangeListener(null);
        holder.enabled.setChecked(entry.isEnabled());
        holder.enabled.setContentDescription(holder.itemView.getContext().getString(
                R.string.enabled,
                entry.getDisplayScheme()
        ));
        holder.enabled.setOnCheckedChangeListener((button, enabled) -> {
            Log.i(TAG, "Alias toggle requested: scheme=" + entry.getScheme() + ", enabled=" + enabled);
            enabledChangedListener.onEnabledChanged(entry, enabled);
        });
        holder.itemView.setOnClickListener(view -> {
            Log.d(TAG, "Scheme details clicked: scheme=" + entry.getScheme());
            entryClickListener.onEntryClick(entry);
        });
        holder.itemView.setOnLongClickListener(view -> {
            Log.d(TAG, "Scheme long-clicked: scheme=" + entry.getScheme());
            entryLongClickListener.onEntryLongClick(entry);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    @NonNull
    private static String buildSubtitle(
            @NonNull Context context,
            @NonNull SchemeManager.SchemeEntry entry
    ) {
        String description = entry.getDescription();
        String installedApps = join(entry.getInstalledAppNames());
        String defaultHandler = entry.getDefaultHandlerName();
        if (!installedApps.isEmpty()) {
            return defaultHandler.isEmpty()
                    ? context.getString(R.string.installed_apps, installedApps)
                    : context.getString(
                            R.string.scheme_details_with_handler,
                            context.getString(R.string.installed_apps, installedApps),
                            defaultHandler
                    );
        }
        return description;
    }

    @NonNull
    private static String join(@NonNull List<String> items) {
        return android.text.TextUtils.join(" / ", items);
    }

    public static final class ViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final android.widget.Button clearDefault;
        final SwitchCompat enabled;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.scheme_title);
            subtitle = itemView.findViewById(R.id.scheme_subtitle);
            clearDefault = itemView.findViewById(R.id.clear_default_button);
            enabled = itemView.findViewById(R.id.scheme_switch);
        }
    }
}
