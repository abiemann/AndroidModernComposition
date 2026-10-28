package dev.fieldnotes.legacy;

import android.os.Bundle;
import androidx.fragment.app.FragmentActivity;
import androidx.activity.EdgeToEdge;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** A Java/Views island used to study incremental Compose migrations. */
public final class LegacyActivity extends FragmentActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_legacy);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.legacy_container), (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                .replace(R.id.legacy_container, new LegacyFragment()).commit();
        }
    }
}
