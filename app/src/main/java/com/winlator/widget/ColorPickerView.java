package com.winlator.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.InputFilter;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import androidx.annotation.Nullable;

import com.winlator.R;
import com.winlator.core.AppUtils;
import com.winlator.core.UnitUtils;

import java.util.Locale;

public class ColorPickerView extends View implements View.OnClickListener {
    public interface OnColorChangeListener {
        /** @param color 0xRRGGBB, or null when "default" was picked (only with {@link #setExtendedMode}). */
        void onColorChange(ColorPickerView view, Integer color);
    }

    // DGPlayer: two rows of eight for the extended popup (Input Controls element colours).
    private static final int[] EXTENDED_PALETTE = {
        0xffffff, 0xbdbdbd, 0x757575, 0x000000, 0xd32f2f, 0xe91e63, 0xff8f00, 0xfdd835,
        0x7cb342, 0x2e7d32, 0x00838f, 0x00bcd4, 0x0277bd, 0x3949ab, 0x9575cd, 0x607d8b
    };
    private static final int EXTENDED_ROW_SIZE = 8;

    private int[] palette = {0xff8f00, 0xd32f2f, 0x9575cd, 0x2e7d32, 0x00838f, 0x0277bd, 0x607d8b, 0x000000};
    private int currentColor = 0xffffff;
    private final Bitmap colorFrame;
    private boolean extendedMode;
    private boolean isDefault;
    private OnColorChangeListener onColorChangeListener;

    public ColorPickerView(Context context) {
        this(context, null);
    }

    public ColorPickerView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ColorPickerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        colorFrame = BitmapFactory.decodeResource(context.getResources(), R.drawable.color_frame);

        setBackgroundResource(R.drawable.combo_box);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(this);
    }

    public int getColor() {
        return toARGB(currentColor);
    }

    public void setColor(int color) {
        currentColor = toRGB(color);
        invalidate();
    }

    /**
     * Extended popup: a "default" cell, a 16-colour palette and a #RRGGBB field.
     * Existing pickers (cursor / desktop colour) keep the original single-row popup.
     */
    public void setExtendedMode(boolean extendedMode) {
        this.extendedMode = extendedMode;
        if (extendedMode) palette = EXTENDED_PALETTE;
    }

    public void setOnColorChangeListener(OnColorChangeListener listener) {
        this.onColorChangeListener = listener;
    }

    /** @return 0xRRGGBB, or null when the default is selected. */
    public Integer getColorOrNull() {
        return isDefault ? null : (currentColor & 0xffffff);
    }

    public void setColorOrNull(Integer color) {
        isDefault = color == null;
        if (color != null) currentColor = toRGB(color);
        invalidate();
    }

    private void pick(Integer color) {
        setColorOrNull(color);
        if (onColorChangeListener != null) onColorChangeListener.onColorChange(this, getColorOrNull());
    }

    private static Integer parseHex(String value) {
        String hex = value.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6) return null;
        try {
            return Integer.parseInt(hex, 16);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private static void drawDefaultMark(Canvas canvas, float left, float top, float right, float bottom) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffffffff);
        canvas.drawRect(left, top, right, bottom, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(UnitUtils.dpToPx(2));
        paint.setColor(0xffd32f2f);
        canvas.drawLine(left, bottom, right, top, paint);
    }

    public String getColorAsString() {
        return String.format(Locale.ENGLISH, "#%06X", (0x00ffffff & currentColor));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;

        float rectSize = height - UnitUtils.dpToPx(12);
        float startX = (width - rectSize) * 0.5f - UnitUtils.dpToPx(16);
        float startY = (height - rectSize) * 0.5f;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (isDefault) {
            drawDefaultMark(canvas, startX, startY, startX + rectSize, startY + rectSize);
        }
        else {
            paint.setColor(toARGB(currentColor));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(startX, startY, startX + rectSize, startY + rectSize, paint);
        }

        Rect srcRect = new Rect(0, 0, colorFrame.getWidth(), colorFrame.getHeight());
        RectF dstRect = new RectF(startX, startY, startX + rectSize, startY + rectSize);
        canvas.drawBitmap(colorFrame, srcRect, dstRect, paint);
    }

    public static int toARGB(int rgb) {
        return Color.argb(255, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    public static int toRGB(int argb) {
        return Color.argb(0, Color.red(argb), Color.green(argb), Color.blue(argb));
    }

    public void setPalette(int... palette) {
        this.palette = palette;
    }

    @Override
    public void onClick(View anchor) {
        if (extendedMode) {
            showExtendedPopup(anchor);
            return;
        }
        Context context = getContext();
        final int popupHeight = 60;
        LinearLayout container = new LinearLayout(context);
        container.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int)UnitUtils.dpToPx(popupHeight)));
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(0, 0, (int)UnitUtils.dpToPx(4), 0);

        Bitmap colorFrameSelected = BitmapFactory.decodeResource(context.getResources(), R.drawable.color_frame_selected);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams((int)UnitUtils.dpToPx(32), (int)UnitUtils.dpToPx(32));
        params.setMargins((int)UnitUtils.dpToPx(4), 0, 0, 0);
        final PopupWindow[] popupWindow = {null};

        for (final int color : palette) {
            ImageView imageView = new ImageView(context);
            imageView.setLayoutParams(params);
            imageView.setImageBitmap(color == currentColor ? colorFrameSelected : colorFrame);
            imageView.setBackgroundColor(toARGB(color));
            imageView.setOnClickListener((v) -> {
                currentColor = color;
                invalidate();
                if (onColorChangeListener != null) onColorChangeListener.onColorChange(this, color);
                if (popupWindow[0] != null) popupWindow[0].dismiss();
            });
            container.addView(imageView);
        }
        popupWindow[0] = AppUtils.showPopupWindow(anchor, container, 0, popupHeight);
    }

    private void showExtendedPopup(View anchor) {
        Context context = getContext();
        final PopupWindow[] popupWindow = {null};
        int padding = (int)UnitUtils.dpToPx(4);
        int cellSize = (int)UnitUtils.dpToPx(32);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, padding, padding, padding);

        Bitmap colorFrameSelected = BitmapFactory.decodeResource(context.getResources(), R.drawable.color_frame_selected);
        LinearLayout row = null;

        // Cell 0 is "default", so the first row holds one more cell than the second.
        for (int i = 0; i <= palette.length; i++) {
            if (i == 0 || i == EXTENDED_ROW_SIZE + 1) {
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, i == 0 ? 0 : padding, 0, 0);
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                container.addView(row, rowParams);
            }

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(cellSize, cellSize);
            params.setMargins(padding, 0, 0, 0);

            if (i == 0) {
                final Bitmap frame = isDefault ? colorFrameSelected : colorFrame;
                View defaultCell = new View(context) {
                    @Override
                    protected void onDraw(Canvas canvas) {
                        drawDefaultMark(canvas, 0, 0, getWidth(), getHeight());
                        canvas.drawBitmap(frame, new Rect(0, 0, frame.getWidth(), frame.getHeight()), new RectF(0, 0, getWidth(), getHeight()), null);
                    }
                };
                defaultCell.setContentDescription(context.getString(R.string.default_color));
                defaultCell.setOnClickListener((v) -> {
                    pick(null);
                    if (popupWindow[0] != null) popupWindow[0].dismiss();
                });
                row.addView(defaultCell, params);
                continue;
            }

            final int color = palette[i - 1];
            ImageView imageView = new ImageView(context);
            imageView.setImageBitmap(!isDefault && color == (currentColor & 0xffffff) ? colorFrameSelected : colorFrame);
            imageView.setBackgroundColor(toARGB(color));
            imageView.setOnClickListener((v) -> {
                pick(color);
                if (popupWindow[0] != null) popupWindow[0].dismiss();
            });
            row.addView(imageView, params);
        }

        LinearLayout hexRow = new LinearLayout(context);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hexRowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hexRowParams.setMargins(padding, padding, 0, 0);

        final EditText etHex = new EditText(context);
        etHex.setSingleLine(true);
        etHex.setHint("#RRGGBB");
        etHex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etHex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7), new InputFilter.AllCaps()});
        etHex.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (!isDefault) etHex.setText(getColorAsString());
        hexRow.addView(etHex, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        final Runnable applyHex = () -> {
            Integer color = parseHex(etHex.getText().toString());
            if (color == null) {
                etHex.setError("#RRGGBB");
                return;
            }
            pick(color);
            if (popupWindow[0] != null) popupWindow[0].dismiss();
        };
        etHex.setOnEditorActionListener((v, actionId, event) -> {
            applyHex.run();
            return true;
        });

        Button btnOk = new Button(context);
        btnOk.setText(R.string.ok);
        btnOk.setOnClickListener((v) -> applyHex.run());
        hexRow.addView(btnOk, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(hexRow, hexRowParams);

        popupWindow[0] = AppUtils.showPopupWindow(anchor, container, 0, 0);
    }
}
