package moe.shuvi.schemesinterceptor;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Local jump history. All database work runs on one background thread. */
final class JumpRecordStore extends SQLiteOpenHelper {
    static final int INTERCEPTED = 0;
    static final int WINDOW_SWITCH = 1;
    static final int PAGE_SIZE = 100;
    private static final String TAG = "JumpRecordStore";
    private static JumpRecordStore instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    static synchronized JumpRecordStore get(Context context) {
        if (instance == null) {
            instance = new JumpRecordStore(context.getApplicationContext());
        }
        return instance;
    }

    private JumpRecordStore(Context context) {
        // URI parameters can be sensitive. Keep the database out of cloud and device backups.
        super(context, new File(context.getNoBackupFilesDir(), "jump-records.db").getPath(), null, 1);
    }

    record Entry(long id, long time, int type, String source, String target, String data) { }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records (id INTEGER PRIMARY KEY, time INTEGER NOT NULL, "
                + "type INTEGER NOT NULL, source TEXT NOT NULL, target TEXT NOT NULL, data TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the only schema. Future migrations must preserve existing history.
    }

    void recordIntercepted(String caller, String target, String uri, Runnable onRecorded) {
        add(new Entry(0, System.currentTimeMillis(), INTERCEPTED, caller, target, uri), onRecorded);
    }

    void recordWindowSwitch(String previousPackage, String nextPackage, String windowClass) {
        add(new Entry(0, System.currentTimeMillis(), WINDOW_SWITCH,
                previousPackage, nextPackage, windowClass), null);
    }

    private void add(Entry entry, Runnable onRecorded) {
        executor.execute(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues values = new ContentValues();
                values.put("time", entry.time());
                values.put("type", entry.type());
                values.put("source", entry.source() == null ? "" : entry.source());
                values.put("target", entry.target());
                values.put("data", entry.data());
                db.insertOrThrow("records", null, values);
            } catch (SQLException exception) {
                // Recording must never prevent an intercepted Activity from finishing.
                Log.w(TAG, "Unable to save a jump record", exception);
            } finally {
                if (onRecorded != null) mainHandler.post(onRecorded);
            }
        });
    }

    /** A null result signals a database failure; callbacks always run on the main thread. */
    void load(String query, int offset, Consumer<List<Entry>> callback) {
        executor.execute(() -> {
            List<Entry> entries = new ArrayList<>();
            String selection = query.isEmpty() ? "" : "WHERE instr(lower(source), lower(?)) > 0 "
                    + "OR instr(lower(target), lower(?)) > 0 OR instr(lower(data), lower(?)) > 0 ";
            String[] args = query.isEmpty()
                    ? new String[]{Integer.toString(PAGE_SIZE), Integer.toString(offset)}
                    : new String[]{query, query, query,
                            Integer.toString(PAGE_SIZE), Integer.toString(offset)};
            try (Cursor cursor = getReadableDatabase().rawQuery(
                    "SELECT id, time, type, source, target, data FROM records " + selection
                            + "ORDER BY id DESC LIMIT ? OFFSET ?", args)) {
                while (cursor.moveToNext()) {
                    entries.add(new Entry(cursor.getLong(0), cursor.getLong(1), cursor.getInt(2),
                            cursor.getString(3), cursor.getString(4), cursor.getString(5)));
                }
                mainHandler.post(() -> callback.accept(entries));
            } catch (SQLException exception) {
                Log.w(TAG, "Unable to load jump records", exception);
                mainHandler.post(() -> callback.accept(null));
            }
        });
    }

    void delete(long id, Consumer<Boolean> callback) {
        executor.execute(() -> {
            boolean success;
            try {
                success = getWritableDatabase().delete("records", "id = ?",
                        new String[]{Long.toString(id)}) == 1;
            } catch (SQLException exception) {
                Log.w(TAG, "Unable to delete jump record", exception);
                success = false;
            }
            boolean result = success;
            mainHandler.post(() -> callback.accept(result));
        });
    }

    void clear(Consumer<Boolean> callback) {
        executor.execute(() -> {
            boolean success;
            try {
                getWritableDatabase().delete("records", null, null);
                success = true;
            } catch (SQLException exception) {
                Log.w(TAG, "Unable to clear jump records", exception);
                success = false;
            }
            boolean result = success;
            mainHandler.post(() -> callback.accept(result));
        });
    }
}
