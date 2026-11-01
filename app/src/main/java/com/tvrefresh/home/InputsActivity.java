package com.tvrefresh.home;

import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where to watch: a tile per source, by the name the TV knows it by. A device on HDMI that says who it is (HDMI-CEC,
 * e.g. "Family Room") gets its own tile in place of its port. Pressing a tile switches the TV to it.
 */
public class InputsActivity extends TileActivity {
    /** The TCL Live TV app plays inputs and channels; it takes a passthrough input as a channel to view. */
    private static final String CHANNEL = "vnd.android.cursor.item/channel";
    private static final String LIVE_TV = "com.tcl.tv";

    private static final class Source {
        final String id, name, note;
        final String[] icon;
        final int state;

        Source(String id, String name, String note, String[] icon, int state) {
            this.id = id;
            this.name = name;
            this.note = note;
            this.icon = icon;
            this.state = state;
        }
    }

    @Override
    void build() {
        TextView title = text(tiles.light, 30, pal.ink);
        title.setLetterSpacing(-0.02f);
        title.setText(R.string.inputs_title);
        root.addView(title, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(X0), dp(54), 0, 0));
        TextView note = label(9, pal.dim());
        note.setText(R.string.inputs_note);
        root.addView(note, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(X0 + 2), dp(98), 0, 0));
        placed.add(title);

        List<Source> sources = sources();
        View first = null;
        for (int i = 0; i < sources.size() && i < 12; i++) {
            View t = tile(sources.get(i), (i % 4) * 2, 1 + i / 4);
            if (first == null && sources.get(i).state != TvInputManager.INPUT_STATE_DISCONNECTED) first = t;
        }
        int n = Math.min(sources.size(), 12);
        View all = action(DotMatrixView.INPUTS, getString(R.string.all_sources), getString(R.string.all_sources_note),
                (n % 4) * 2, 1 + n / 4, 2, () -> open(new Intent("com.android.tv.action.VIEW_INPUTS")));
        (first != null ? first : all).post((first != null ? first : all)::requestFocus);
    }

    /** CEC devices first, then the ports and other sockets, then the aerial; hidden inputs left out. */
    private List<Source> sources() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<Source> devices = new ArrayList<>(), ports = new ArrayList<>(), tuner = new ArrayList<>();
        if (tim == null) return devices;
        List<TvInputInfo> all = tim.getTvInputList();
        Set<String> hasDevice = new HashSet<>();
        for (TvInputInfo i : all) if (i.getParentId() != null) hasDevice.add(i.getParentId());
        for (TvInputInfo i : all) {
            if (i.isHidden(this)) continue;
            String name = label(i);
            int state = tim.getInputState(i.getId());
            if (i.getParentId() != null) {
                TvInputInfo port = find(all, i.getParentId());
                devices.add(new Source(i.getId(), name, port == null ? "" : label(port), DotMatrixView.BOX, state));
            } else if (i.isPassthroughInput()) {
                if (hasDevice.contains(i.getId())) continue;    // its device has a tile already
                ports.add(new Source(i.getId(), name, "", i.getType() == TvInputInfo.TYPE_HDMI ? DotMatrixView.HDMI : DotMatrixView.INPUTS, state));
            } else if (i.getType() == TvInputInfo.TYPE_TUNER) {
                tuner.add(new Source(null, getString(R.string.live_tv), name, DotMatrixView.SCREEN, TvInputManager.INPUT_STATE_CONNECTED));
            }
        }
        devices.addAll(ports);
        if (!tuner.isEmpty()) devices.add(tuner.get(0));
        return devices;
    }

    private String label(TvInputInfo i) {
        CharSequence custom = i.loadCustomLabel(this);
        return String.valueOf(custom != null && custom.length() > 0 ? custom : i.loadLabel(this));
    }

    private static TvInputInfo find(List<TvInputInfo> all, String id) {
        for (TvInputInfo i : all) if (i.getId().equals(id)) return i;
        return null;
    }

    private View tile(Source s, int col, int row) {
        boolean off = s.state == TvInputManager.INPUT_STATE_DISCONNECTED;
        FrameLayout t = place(tiles.tile(false, true), col, row, 2, 1);
        Tiles.fill(t, pal.surface);
        DotMatrixView icon = new DotMatrixView(this, dp(DotMatrixView.ICON_PITCH));
        icon.setPattern(s.icon, false);
        icon.setColors(off ? pal.dim() : pal.ink, 0);
        t.addView(icon, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(16), dp(16), 0, 0));
        if (!off) {
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(s.state == TvInputManager.INPUT_STATE_CONNECTED ? Palette.ACCENT : pal.dim());
            dot.setBackground(g);
            t.addView(dot, Tiles.at(Gravity.TOP | Gravity.END, dp(7), dp(7), 0, dp(19), dp(18), 0));
        }
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(tiles.medium, 14, off ? pal.dim() : pal.ink);
        name.setText(s.name);
        words.addView(name, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView small = label(8.5f, pal.dim());
        String state = getString(off ? R.string.input_none : s.state == TvInputManager.INPUT_STATE_CONNECTED
                ? R.string.input_connected : R.string.input_standby);
        small.setText(s.note.isEmpty() ? state : s.note + "  ·  " + state);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(5);
        words.addView(small, lp);
        t.addView(words, Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(16), 0, dp(12), dp(14)));
        t.setContentDescription(s.name);
        t.setOnClickListener(v -> {
            Tiles.press(v);
            if (s.id == null) {
                Intent live = getPackageManager().getLeanbackLaunchIntentForPackage(LIVE_TV);
                if (live == null) live = getPackageManager().getLaunchIntentForPackage(LIVE_TV);
                if (live != null) open(live);
            } else {
                open(new Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.buildChannelUriForPassthroughInput(s.id), CHANNEL));
            }
        });
        return t;
    }
}
