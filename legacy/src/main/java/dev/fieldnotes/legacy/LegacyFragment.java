package dev.fieldnotes.legacy;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import org.xmlpull.v1.XmlPullParser;

public final class LegacyFragment extends Fragment {
    private TextView summary;
    private int visits;
    public LegacyFragment() { super(R.layout.fragment_legacy); }

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        visits = state == null ? 0 : state.getInt("visits", 0);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        androidx.core.view.ViewCompat.setAccessibilityHeading(view.findViewById(R.id.legacy_title), true);
        summary = view.findViewById(R.id.legacy_summary);
        view.findViewById(R.id.legacy_close).setOnClickListener(v -> requireActivity().finish());
        // A tiny bundled XML resource illustrates an existing XML data contract.
        // Real files/network parsing belong on an IO dispatcher, behind a repository.
        try (android.content.res.XmlResourceParser parser = getResources().getXml(R.xml.legacy_observation)) {
            String title = "";
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() == XmlPullParser.START_TAG && "observation".equals(parser.getName())) {
                    title = parser.getAttributeValue(null, "title");
                }
            }
            ((TextView) view.findViewById(R.id.legacy_import)).setText(getString(R.string.legacy_import, title));
        } catch (Exception error) {
            ((TextView) view.findViewById(R.id.legacy_import)).setText(R.string.legacy_parse_error);
        }
    }

    @Override public void onResume() {
        super.onResume();
        visits++;
        summary.setText(getString(R.string.legacy_visits, LegacyFormatter.count(visits)));
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        state.putInt("visits", visits);
        super.onSaveInstanceState(state);
    }

    @Override public void onDestroyView() {
        summary = null; // Fragment lifetime can exceed its View lifetime.
        super.onDestroyView();
    }
}
