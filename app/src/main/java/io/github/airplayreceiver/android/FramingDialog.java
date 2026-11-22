package io.github.airplayreceiver.android;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import io.github.airplayreceiver.R;

/** A remote-first picture menu with immediate, persistent adjustments. */
final class FramingDialog {
    interface ApplyFraming {
        void apply(MirrorFraming framing);
    }

    private static final int MORE = -1;
    private static final int PANEL_COLOR = 0xFF141924;
    private static final int BUTTON_COLOR = 0xFF242B38;
    private static final int ACTIVE_COLOR = 0xFF193A38;
    private static final int FOCUS_COLOR = 0xFFE7EEFF;
    private static final int ACTIVE_TEXT = 0xFFD0FFF1;

    private static final int[][] MODES = {
            {R.string.framing_fit, R.string.framing_fit_help},
            {R.string.framing_wide, R.string.framing_wide_help},
            {R.string.framing_manual, R.string.framing_manual_help},
            {R.string.framing_auto, R.string.framing_auto_help},
            {R.string.framing_fill, R.string.framing_fill_help},
            {R.string.framing_stretch, R.string.framing_stretch_help},
            {R.string.framing_actual, R.string.framing_actual_help},
            {R.string.framing_4_3, R.string.framing_ratio_help},
            {R.string.framing_14_9, R.string.framing_ratio_help},
            {R.string.framing_16_9, R.string.framing_ratio_help},
            {R.string.framing_nonlinear, R.string.framing_nonlinear_help},
            {R.string.framing_zoom_pan, R.string.framing_zoom_pan_help}
    };

    private final Activity activity;
    private final ApplyFraming apply;
    private final boolean autoCropLocked;
    private final Runnable redetectAutoCrop;
    private final int[] edgeValues;
    private final int[] zoomAndPan;
    private final Dialog dialog;
    private Runnable back;
    private int mode;
    private int rotation;

    FramingDialog(Activity activity, MirrorFraming current, ApplyFraming apply, boolean autoCropLocked,
                  Runnable redetectAutoCrop) {
        this.activity = activity;
        this.apply = apply;
        this.autoCropLocked = autoCropLocked;
        this.redetectAutoCrop = redetectAutoCrop;
        mode = current.mode;
        rotation = current.rotation;
        edgeValues = new int[] {current.left, current.top, current.right, current.bottom};
        zoomAndPan = new int[] {current.zoom, current.offsetX, current.offsetY};
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnKeyListener((window, key, event) -> {
            if (key == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP) dialog.dismiss();
                return true;
            }
            if (key == KeyEvent.KEYCODE_BACK && back != null) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) back.run();
                return true;
            }
            return false;
        });
    }

    Dialog show() {
        showQuickModes(false);
        return dialog;
    }

    private void showQuickModes(boolean focusMore) {
        back = null;
        LinearLayout panel = panel(R.string.framing_title, R.string.framing_quick_hint);
        LinearLayout cards = new LinearLayout(activity);
        panel.addView(cards);
        TextView help = helpText();
        int[] choices = {MirrorFraming.AUTO, MirrorFraming.FIT, MirrorFraming.FILL, MORE};
        Button initial = null;
        for (int choice : choices) {
            boolean more = choice == MORE;
            boolean selected = more ? !isQuickMode(mode) : choice == mode;
            int title = more ? R.string.framing_more : MODES[choice][0];
            int description = more ? R.string.framing_more_help : MODES[choice][1];
            if (choice == MirrorFraming.AUTO && mode == choice && autoCropLocked) {
                description = R.string.framing_auto_locked_help;
            }
            Button card = button(label(title, selected), selected);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(6), dp(11), dp(6), dp(8));
            card.setCompoundDrawablesWithIntrinsicBounds(null, pictureIcon(choice), null, null);
            card.setCompoundDrawablePadding(dp(6));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(82), 1);
            if (choice != choices[0]) params.setMarginStart(dp(8));
            cards.addView(card, params);
            describeOnFocus(card, help, description);
            card.setOnClickListener(view -> {
                if (more) showMoreModes();
                else selectMode(choice);
            });
            if ((focusMore && more) || (!focusMore && selected)) initial = card;
        }
        panel.addView(help);
        present(panel, ViewGroup.LayoutParams.WRAP_CONTENT, initial);
    }

    private void showMoreModes() {
        back = () -> showQuickModes(true);
        LinearLayout panel = panel(R.string.framing_more_title, R.string.framing_select_hint);
        LinearLayout list = column();
        TextView help = helpText();

        Button rotationButton = row(activity.getString(R.string.framing_rotate, rotation * 90), false);
        describeOnFocus(rotationButton, help, R.string.framing_rotate_help);
        rotationButton.setOnClickListener(view -> {
            rotation = (rotation + 1) % 4;
            rotationButton.setText(activity.getString(R.string.framing_rotate, rotation * 90));
            apply.apply(framing());
        });
        list.addView(rotationButton);

        Button current = rotationButton;
        for (int index = 0; index < MODES.length; index++) {
            if (isQuickMode(index)) continue;
            final int choice = index;
            boolean selected = index == mode;
            Button button = row(label(MODES[index][0], selected), selected);
            describeOnFocus(button, help, MODES[index][1]);
            button.setOnClickListener(view -> selectMode(choice));
            list.addView(button);
            if (selected) current = button;
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        panel.addView(help);
        present(panel, detailHeight(), current);
    }

    private void present(LinearLayout panel, int height, View focused) {
        dialog.setContentView(panel);
        if (!dialog.isShowing()) dialog.show();
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.y = dp(20);
        window.setAttributes(attributes);
        window.setLayout(Math.min(dp(900), activity.getResources().getDisplayMetrics().widthPixels * 9 / 10), height);
        window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
        focused.requestFocus();
    }

    private void selectMode(int choice) {
        if (choice == MirrorFraming.AUTO && mode == choice) redetectAutoCrop.run();
        mode = choice;
        apply.apply(framing());
        if (mode == MirrorFraming.MANUAL || mode == MirrorFraming.ZOOM_AND_PAN) {
            showAdjustments();
        } else {
            dialog.dismiss();
        }
    }

    private void showAdjustments() {
        back = this::showMoreModes;
        LinearLayout panel = panel(MODES[mode][0], R.string.framing_adjust_hint);
        LinearLayout controls = column();
        if (mode == MirrorFraming.MANUAL) {
            int[] edgeLabels = {R.string.crop_left, R.string.crop_top, R.string.crop_right, R.string.crop_bottom};
            for (int i = 0; i < edgeLabels.length; i++) {
                addSlider(controls, edgeLabels[i], edgeValues, i, 0, MirrorFraming.MAX_EDGE_PERCENT, 1);
            }
        } else {
            addSlider(controls, R.string.framing_zoom_level, zoomAndPan, 0, 50, 200, 5);
            addSlider(controls, R.string.framing_offset_x, zoomAndPan, 1, -30, 30, 1);
            addSlider(controls, R.string.framing_offset_y, zoomAndPan, 2, -30, 30, 1);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(controls);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        present(panel, detailHeight(), controls.getChildAt(1));
    }

    private MirrorFraming framing() {
        return new MirrorFraming(mode, edgeValues[0], edgeValues[1], edgeValues[2], edgeValues[3],
                rotation, zoomAndPan[0], zoomAndPan[1], zoomAndPan[2]);
    }

    private LinearLayout panel(int title, int hint) {
        LinearLayout panel = column();
        panel.setBackground(rounded(PANEL_COLOR, 17, 1, 0xFF343C4C));
        panel.setPadding(dp(16), dp(12), dp(16), dp(8));
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text(19, R.color.text_primary);
        heading.setText(title);
        heading.setTypeface(null, Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView instructions = text(14, R.color.text_secondary);
        instructions.setText(hint);
        header.addView(instructions);
        header.setPadding(0, 0, 0, dp(10));
        panel.addView(header);
        return panel;
    }

    private Button button(String label, boolean selected) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextSize(17);
        button.setTextColor(selected ? ACTIVE_TEXT : Color.WHITE);
        button.setSelected(selected);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setFocusable(true);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        styleButton(button, false);
        return button;
    }

    private Button row(String label, boolean selected) {
        Button button = button(label, selected);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        params.bottomMargin = dp(4);
        button.setLayoutParams(params);
        return button;
    }

    private void describeOnFocus(View row, TextView help, int description) {
        row.setOnFocusChangeListener((view, focused) -> {
            styleButton(view, focused);
            if (focused) help.setText(description);
        });
    }

    private void styleButton(View button, boolean focused) {
        button.setBackground(rounded(button.isSelected() ? ACTIVE_COLOR : BUTTON_COLOR,
                10, focused ? 2 : 1, focused ? FOCUS_COLOR : 0xFF354052));
    }

    private GradientDrawable rounded(int fill, int radius, int strokeWidth, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(strokeWidth), stroke);
        return background;
    }

    private BitmapDrawable pictureIcon(int choice) {
        Bitmap icon = Bitmap.createBitmap(dp(54), dp(30), Bitmap.Config.ARGB_8888);
        icon.setDensity(activity.getResources().getDisplayMetrics().densityDpi);
        Canvas canvas = new Canvas(icon);
        canvas.scale(canvas.getWidth() / 54f, canvas.getHeight() / 30f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(FOCUS_COLOR);
        if (choice == MORE) {
            for (int x = 17; x <= 37; x += 10) canvas.drawCircle(x, 15, 2, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            canvas.drawRoundRect(2, 1, 52, 29, 4, 4, paint);
            paint.setStyle(Paint.Style.FILL);
            if (choice == MirrorFraming.FIT) canvas.drawRoundRect(9, 4, 45, 26, 2, 2, paint);
            else if (choice == MirrorFraming.FILL) canvas.drawRoundRect(4, 3, 50, 27, 2, 2, paint);
            else {
                canvas.drawRoundRect(4, 8, 50, 22, 1, 1, paint);
                paint.setAlpha(110);
                canvas.drawRect(4, 4, 50, 6, paint);
                canvas.drawRect(4, 24, 50, 26, paint);
            }
        }
        return new BitmapDrawable(activity.getResources(), icon);
    }

    private String label(int title, boolean selected) {
        String name = activity.getString(title);
        return selected ? activity.getString(R.string.framing_active, name) : name;
    }

    private TextView helpText() {
        TextView help = text(15, R.color.text_secondary);
        help.setMinHeight(dp(35));
        help.setGravity(Gravity.CENTER_VERTICAL);
        return help;
    }

    private int detailHeight() {
        return Math.min(dp(340), activity.getResources().getDisplayMetrics().heightPixels * 3 / 5);
    }

    private static boolean isQuickMode(int choice) {
        return choice == MirrorFraming.AUTO || choice == MirrorFraming.FIT || choice == MirrorFraming.FILL;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(int size, int color) {
        TextView view = new TextView(activity);
        view.setTextSize(size);
        view.setTextColor(activity.getResources().getColor(color));
        return view;
    }

    private int dp(int size) {
        return Math.round(size * activity.getResources().getDisplayMetrics().density);
    }

    private void addSlider(LinearLayout parent, int labelResource, int[] values, int index,
                           int minimum, int maximum, int step) {
        String name = activity.getString(labelResource);
        TextView label = text(17, R.color.text_primary);
        label.setText(activity.getString(R.string.crop_percent, name, values[index]));
        parent.addView(label);
        SeekBar slider = new SeekBar(activity);
        slider.setContentDescription(name);
        slider.setFocusable(true);
        slider.setBackgroundResource(R.drawable.television_button);
        slider.setPadding(dp(12), 0, dp(12), 0);
        slider.setMax((maximum - minimum) / step);
        slider.setKeyProgressIncrement(1);
        slider.setProgress((values[index] - minimum) / step);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        params.bottomMargin = dp(12);
        parent.addView(slider, params);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                values[index] = minimum + progress * step;
                label.setText(activity.getString(R.string.crop_percent, name, values[index]));
                apply.apply(framing());
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
    }
}
