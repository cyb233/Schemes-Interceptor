package moe.shuvi.schemesinterceptor;

import android.content.Context;
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
    public interface OnEnabledChangedListener {
        void onEnabledChanged(@NonNull SchemeManager.SchemeEntry entry, boolean enabled);
    }

    public interface OnClearDefaultListener {
        void onClearDefault(@NonNull SchemeManager.SchemeEntry entry);
    }

    private final List<SchemeManager.SchemeEntry> entries = new ArrayList<>();
    private final OnEnabledChangedListener enabledChangedListener;
    private final OnClearDefaultListener clearDefaultListener;

    public SchemeAdapter(
            @NonNull OnEnabledChangedListener enabledChangedListener,
            @NonNull OnClearDefaultListener clearDefaultListener
    ) {
        this.enabledChangedListener = enabledChangedListener;
        this.clearDefaultListener = clearDefaultListener;
        setHasStableIds(true);
    }

    public void submitList(@NonNull List<SchemeManager.SchemeEntry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        notifyDataSetChanged();
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
        holder.subtitle.setText(buildSubtitle(holder.itemView.getContext(), entry));
        holder.subtitle.setVisibility(holder.subtitle.getText().length() == 0
                ? View.GONE
                : View.VISIBLE);

        holder.clearDefault.setVisibility(entry.getDefaultHandlerPackage().isEmpty()
                ? View.GONE
                : View.VISIBLE);
        holder.clearDefault.setOnClickListener(entry.getDefaultHandlerPackage().isEmpty()
                ? null
                : view -> clearDefaultListener.onClearDefault(entry));

        // Detach before changing checked state: RecyclerView reuses views and
        // setChecked() would otherwise persist an unintended component state.
        holder.enabled.setOnCheckedChangeListener(null);
        holder.enabled.setChecked(entry.isEnabled());
        holder.enabled.setContentDescription(holder.itemView.getContext().getString(
                R.string.enabled,
                entry.getDisplayScheme()
        ));
        holder.enabled.setOnCheckedChangeListener((button, enabled) ->
                enabledChangedListener.onEnabledChanged(entry, enabled));
        holder.itemView.setOnClickListener(view -> holder.enabled.performClick());
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
        String details = installedApps.isEmpty()
                ? ""
                : context.getString(R.string.installed_apps, installedApps);
        if (!defaultHandler.isEmpty()) {
            details = details.isEmpty()
                    ? context.getString(R.string.current_handler, defaultHandler)
                    : context.getString(R.string.scheme_details_with_handler, details, defaultHandler);
        }
        if (description.isEmpty()) {
            return details;
        }
        return details.isEmpty()
                ? description
                : context.getString(R.string.scheme_description_with_apps, description, details);
    }

    @NonNull
    private static String join(@NonNull List<String> items) {
        StringBuilder result = new StringBuilder();
        for (String item : items) {
            if (result.length() > 0) {
                result.append(" / ");
            }
            result.append(item);
        }
        return result.toString();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
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
