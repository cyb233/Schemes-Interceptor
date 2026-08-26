package moe.shuvi.schemesinterceptor;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/** A transparent endpoint used by every generated URL-scheme activity alias. */
public final class BlankActivity extends AppCompatActivity {
    private static final String TAG = "BlankActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: action=" + getIntent().getAction()
                + ", scheme=" + getIntent().getScheme());
        showInterceptedIntent(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent: action=" + intent.getAction()
                + ", scheme=" + intent.getScheme());
        setIntent(intent);
        showInterceptedIntent(intent);
        finish();
    }

    @NonNull
    private String resolveAppLabel(@Nullable String callerPackage) {
        if (callerPackage == null || callerPackage.isEmpty()) {
            return "";
        }
        try {
            CharSequence label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(callerPackage, 0)
            );
            return label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            Log.w(TAG, "Could not resolve caller package label: " + callerPackage);
            return callerPackage;
        }
    }
    @Nullable
    private String findCallerPackage(@NonNull Intent intent) {
        String callingPackage = getCallingPackage();
        if (callingPackage != null && !callingPackage.isEmpty()) {
            return callingPackage;
        }
        Uri referrer = getReferrer();
        if (referrer != null && "android-app".equals(referrer.getScheme())) {
            return referrer.getHost();
        }
        String referrerName = intent.getStringExtra(Intent.EXTRA_REFERRER_NAME);
        if (referrerName != null && referrerName.startsWith("android-app://")) {
            return Uri.parse(referrerName).getHost();
        }
        return null;
    }
    private void showInterceptedIntent(@Nullable Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }
        Uri uri = intent.getData();
        String caller = resolveAppLabel(findCallerPackage(intent));
        if (caller.isEmpty()) {
            caller = getString(R.string.unknown_caller);
            Log.d(TAG, "Intercepted scheme without an attributable caller: " + uri.getScheme());
        } else {
            Log.i(TAG, "Intercepted scheme=" + uri.getScheme() + " from caller=" + caller);
        }
        Toast.makeText(
                this,
                getString(R.string.intercepted_scheme, caller, uri.getScheme()),
                Toast.LENGTH_SHORT
        ).show();
    }
}
