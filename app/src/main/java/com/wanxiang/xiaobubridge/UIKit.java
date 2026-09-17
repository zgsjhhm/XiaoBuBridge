package com.wanxiang.xiaobubridge;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * 复刻自参照物 j477si.apk（Qwen AppHook）的 UI 基元。
 *
 * <p>参照物是 Jetpack Compose + Material3 实现，本工程为纯 Java/View，
 * 无法直接复用 Compose 组件，因此把参照物的视觉规格逐项翻译成 View 层
 * 的工具方法，保证两套技术栈下呈现一致：</p>
 *
 * <table>
 *   <tr><th>参照物</th><th>本类对应</th></tr>
 *   <tr><td>{@code lightColorScheme(primary, surfaceContainer, onSurface…)}</td>
 *       <td>{@link #ACCENT} / {@link #BG} / {@link #TEXT_PRIMARY} …</td></tr>
 *   <tr><td>{@code TopAppBar(title, action="刷新")}</td><td>{@link #topBar}</td></tr>
 *   <tr><td>{@code NavigationBar + NavigationBarItem(icon,label)}</td><td>{@link #navBar}</td></tr>
 *   <tr><td>{@code Card(RoundedCornerShape(12), surfaceContainer)</td><td>{@link #card}</td></tr>
 *   <tr><td>{@code SectionTitle} / {@code InfoRow} / {@code CreditRow}</td>
 *       <td>{@link #sectionTitle} / {@link #infoRow} / {@link #creditRow}</td></tr>
 * </table>
 *
 * <p>取值与 {@code res/values/colors.xml} 中的 {@code xb_*} 常量一致；
 * 这里以 int 常量再声明一份，是为了让 UI 代码不必在每次取色时都走
 * {@code getResources().getColor()}（面板在目标 App 进程里跨包取色，
 * 走资源通道会引入不必要的 Context 依赖）。</p>
 */
final class UIKit {

    private UIKit() {
    }

    // ==================== 设计令牌（浅色，与参照物一致） ====================

    static final int BG = 0xFFF8FAFC;
    static final int SURFACE = 0xFFFFFFFF;
    static final int SURFACE_BORDER = 0xFFE2E8F0;
    static final int ACCENT = 0xFF3B82F6;
    static final int ACCENT_DIM = 0xFFDBEAFE;
    static final int TEXT_PRIMARY = 0xFF1E293B;
    static final int TEXT_SECONDARY = 0xFF64748B;
    static final int TEXT_HINT = 0xFF94A3B8;
    static final int DIVIDER = 0xFFE2E8F0;
    static final int INPUT_BG = 0xFFF1F5F9;
    static final int INPUT_BORDER = 0xFFCBD5E1;

    static final int GREEN = 0xFF10B981;
    static final int YELLOW = 0xFFF59E0B;
    static final int RED = 0xFFEF4444;
    static final int PURPLE = 0xFF8B5CF6;
    static final int WHITE = 0xFFFFFFFF;

    /** 卡片圆角，对应参照物 {@code RoundedCornerShape(12)} */
    static final float R_CARD = 12f;
    /** 按钮 / 分段选择器圆角 */
    static final float R_CONTROL = 10f;
    /** 输入框圆角 */
    static final float R_INPUT = 10f;

    /** 主色强调的正文（对应 Compose 的 bodyMedium / 13sp） */
    static final float SP_BODY = 13f;
    /** 辅助说明文字（12sp） */
    static final float SP_HINT = 12f;
    /** 组标题（13sp Bold，参照物 SectionTitle） */
    static final float SP_SECTION = 13f;

    // ==================== 度量 ====================

    static int dp(Context ctx, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, ctx.getResources().getDisplayMetrics()));
    }

    static LinearLayout.LayoutParams matchWrap(Context ctx, float topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, topMarginDp);
        return lp;
    }

    // ==================== 根容器 ====================

    /** 页面根：竖排 + 参照物面板底色 */
    static LinearLayout root(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        return root;
    }

    /**
     * 顶栏（对应参照物 {@code TopAppBar}）。
     *
     * <p>参照物顶栏左侧为标题、右侧是一个文案为「刷新」的 {@code IconButton}；
     * 这里用同色的 TextView 作动作按钮，视觉等价且不需要额外的图标资源。</p>
     */
    static LinearLayout topBar(Context ctx, String title, String actionText, View.OnClickListener onAction) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(SURFACE);
        bar.setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12));

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(TEXT_PRIMARY);
        bar.addView(tvTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (actionText != null && onAction != null) {
            TextView tvAction = new TextView(ctx);
            tvAction.setText(actionText);
            tvAction.setTextSize(SP_BODY);
            tvAction.setTextColor(ACCENT);
            tvAction.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6));
            tvAction.setOnClickListener(onAction);
            bar.addView(tvAction, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 顶栏下沿分隔线，对应 Material3 TopAppBar 的 elevation 边界
        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(ctx);
        line.setBackgroundColor(DIVIDER);
        wrapper.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.5f))));
        return wrapper;
    }

    /** 导航项选中回调 */
    interface OnNavSelect {
        void onSelect(int index);
    }

    /**
     * 底部导航（对应参照物 {@code NavigationBar}）。
     *
     * <p>参照物每个 {@code NavigationBarItem} 是「选中态药丸 + 图标 + 文案」，
     * 选中态取 primary，未选中取 {@link #TEXT_HINT}，药丸取 accentDim。</p>
     *
     * @param icons 与 labels 等长的 drawable 资源 id，传 0 表示该位不显示图标
     */
    static LinearLayout navBar(Context ctx, String[] labels, int[] icons,
                               int selected, OnNavSelect callback) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(SURFACE);
        bar.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));

        View[] pillRefs = new View[labels.length];
        ImageView[] iconRefs = new ImageView[labels.length];
        TextView[] labelRefs = new TextView[labels.length];

        for (int i = 0; i < labels.length; i++) {
            final int index = i;

            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);

            // 图标药丸：选中时填充 accentDim，未选中为透明
            FrameLayout pill = new FrameLayout(ctx);
            pill.setBackground(pillBackground(ctx, false));
            int pillW = dp(ctx, 64);
            int pillH = dp(ctx, 30);
            FrameLayout.LayoutParams pillLp =
                    new FrameLayout.LayoutParams(pillW, pillH, Gravity.CENTER);
            ImageView icon = null;
            if (icons != null && i < icons.length && icons[i] != 0) {
                icon = new ImageView(ctx);
                icon.setImageResource(icons[i]);
                icon.setLayoutParams(new FrameLayout.LayoutParams(
                        dp(ctx, 20), dp(ctx, 20), Gravity.CENTER));
                pill.addView(icon);
            }
            iconRefs[i] = icon;
            item.addView(pill, new LinearLayout.LayoutParams(pillW, pillH));
            pillRefs[i] = pill;

            TextView label = new TextView(ctx);
            label.setText(labels[i]);
            label.setTextSize(11f);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(ctx, 2), 0, 0);
            item.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            labelRefs[i] = label;

            item.setOnClickListener(v -> callback.onSelect(index));
            bar.addView(item, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        applyNavSelection(ctx, selected, pillRefs, iconRefs, labelRefs);
        return bar;
    }

    /** 就地刷新导航选中态（避免重建整条导航栏） */
    static void applyNavSelection(Context ctx, int selected, View[] pills,
                                  ImageView[] icons, TextView[] labels) {
        for (int i = 0; i < pills.length; i++) {
            boolean on = (i == selected);
            if (pills[i] != null) pills[i].setBackground(pillBackground(ctx, on));
            if (icons[i] != null) icons[i].setImageTintList(
                    ColorStateList.valueOf(on ? ACCENT : TEXT_HINT));
            if (labels[i] != null) labels[i].setTextColor(on ? ACCENT : TEXT_HINT);
        }
    }

    private static GradientDrawable pillBackground(Context ctx, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(ctx, 15));
        bg.setColor(selected ? ACCENT_DIM : Color.TRANSPARENT);
        return bg;
    }

    // ==================== 卡片 ====================

    /**
     * 卡片容器（对应参照物 {@code Card(RoundedCornerShape(12), surfaceContainer)}）。
     * 白底、1px 描边、12dp 圆角。
     */
    static LinearLayout card(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 14);
        box.setPadding(pad, pad, pad, pad);
        box.setBackground(cardBackground(ctx));
        box.setLayoutParams(matchWrap(ctx, 12f));
        return box;
    }

    private static GradientDrawable cardBackground(Context ctx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(ctx, R_CARD));
        bg.setColor(SURFACE);
        bg.setStroke(Math.max(1, dp(ctx, 1)), SURFACE_BORDER);
        return bg;
    }

    // ==================== 文本与行 ====================

    /** 组标题（对应参照物 {@code SectionTitle}：primary 色 + Bold + 13sp） */
    static TextView sectionTitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(SP_SECTION);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(ACCENT);
        tv.setPadding(dp(ctx, 4), dp(ctx, 4), 0, dp(ctx, 10));
        return tv;
    }

    /**
     * 键值行（对应参照物 {@code InfoRow}：两列 SpaceBetween）。
     * 返回的就是 value 那个 TextView，便于后续刷新状态。
     */
    static TextView infoRow(LinearLayout card, Context ctx, String label, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4));

        TextView tvLabel = new TextView(ctx);
        tvLabel.setText(label);
        tvLabel.setTextSize(SP_BODY);
        tvLabel.setTextColor(TEXT_SECONDARY);
        row.addView(tvLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvValue = new TextView(ctx);
        tvValue.setText(value == null ? "" : value);
        tvValue.setTextSize(SP_BODY);
        tvValue.setTextColor(TEXT_PRIMARY);
        tvValue.setGravity(Gravity.END);
        row.addView(tvValue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(row, matchWrap(ctx, 0f));
        return tvValue;
    }

    /** 署名行（对应参照物 {@code CreditRow}：左 role、右 name） */
    static void creditRow(LinearLayout card, Context ctx, String role, String name) {
        infoRow(card, ctx, role, name);
    }
    /** 正文段落（可用 {@code \n} 换行，参照物里用于「说明」「功能说明」卡） */
    static TextView body(LinearLayout card, Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(SP_BODY);
        tv.setTextColor(TEXT_PRIMARY);
        tv.setLineSpacing(dp(ctx, 6), 1f);
        card.addView(tv, matchWrap(ctx, 2f));
        return tv;
    }

    /** 次要说明文字（12sp / textSecondary） */
    static TextView hint(LinearLayout card, Context ctx, String text, int bottomDp) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(SP_HINT);
        tv.setTextColor(TEXT_SECONDARY);
        tv.setLineSpacing(dp(ctx, 2), 1f);
        card.addView(tv, matchWrap(ctx, bottomDp));
        return tv;
    }

    /** 等宽小字（API 示例、文件清单等） */
    static TextView mono(LinearLayout card, Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(TEXT_SECONDARY);
        tv.setBackgroundColor(INPUT_BG);
        int pad = dp(ctx, 10);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);
        card.addView(tv, matchWrap(ctx, 6f));
        return tv;
    }

    /** 卡片内分隔线 */
    static void divider(LinearLayout card, Context ctx) {
        View line = new View(ctx);
        line.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.5f)));
        lp.topMargin = dp(ctx, 10);
        lp.bottomMargin = dp(ctx, 10);
        card.addView(line, lp);
    }

    /** 纯高度占位 */
    static void spacer(LinearLayout parent, Context ctx, int heightDp) {
        View v = new View(ctx);
        parent.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)));
    }

    // ==================== 交互控件 ====================

    /** 主操作按钮（对应参照物 {@code Button(colors = primaryContainer)} 的全宽按钮） */
    static MaterialButton primaryButton(Context ctx, String text, View.OnClickListener onClick) {
        MaterialButton btn = new MaterialButton(ctx);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(SP_BODY);
        btn.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        btn.setTextColor(WHITE);
        btn.setCornerRadius(dp(ctx, R_CONTROL));
        btn.setOnClickListener(onClick);
        return btn;
    }

    /** 次级按钮：白底主色描边 */
    static MaterialButton outlineButton(Context ctx, String text, View.OnClickListener onClick) {
        MaterialButton btn = new MaterialButton(ctx);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(SP_BODY);
        btn.setBackgroundTintList(ColorStateList.valueOf(SURFACE));
        btn.setTextColor(ACCENT);
        btn.setStrokeColor(ColorStateList.valueOf(ACCENT));
        btn.setStrokeWidth(dp(ctx, 1));
        btn.setCornerRadius(dp(ctx, R_CONTROL));
        btn.setOnClickListener(onClick);
        return btn;
    }

    /**
     * 开关行（参照物里的 {@code switchRow}）：标题 + 说明 + Switch，整卡一行。
     *
     * @return Switch 控件本身，便于调用方读取/回填状态
     */
    static SwitchMaterial switchRow(LinearLayout card, Context ctx, String title,
                                     String desc, boolean checked) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(0, 0, dp(ctx, 12), 0);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(SP_BODY);
        tvTitle.setTextColor(TEXT_PRIMARY);
        text.addView(tvTitle);

        if (desc != null && !desc.isEmpty()) {
            TextView tvDesc = new TextView(ctx);
            tvDesc.setText(desc);
            tvDesc.setTextSize(11f);
            tvDesc.setTextColor(TEXT_HINT);
            tvDesc.setLineSpacing(dp(ctx, 2), 1f);
            tvDesc.setPadding(0, dp(ctx, 2), 0, 0);
            text.addView(tvDesc);
        }
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        SwitchMaterial sw = new SwitchMaterial(ctx);
        sw.setChecked(checked);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(row, matchWrap(ctx, 0f));
        return sw;
    }

    /**
     * 输入行（参照物里的 {@code inputRow}）：标签 + 灰底圆角输入框。
     *
     * @param inputType 例如 {@link InputType#TYPE_CLASS_NUMBER}，0 表示默认文本
     * @return EditText 控件本身
     */
    static EditText inputRow(LinearLayout card, Context ctx, String label,
                             String initial, int inputType, boolean multiline) {
        if (label != null && !label.isEmpty()) {
            TextView tvLabel = new TextView(ctx);
            tvLabel.setText(label);
            tvLabel.setTextSize(SP_HINT);
            tvLabel.setTextColor(TEXT_SECONDARY);
            tvLabel.setPadding(0, dp(ctx, 10), 0, dp(ctx, 4));
            card.addView(tvLabel);
        }

        EditText et = new EditText(ctx);
        et.setText(initial == null ? "" : initial);
        et.setTextSize(SP_BODY);
        et.setTextColor(TEXT_PRIMARY);
        et.setHintTextColor(TEXT_HINT);
        if (inputType != 0) {
            et.setInputType(inputType);
        }
        if (multiline) {
            et.setMinLines(2);
            et.setGravity(Gravity.TOP | Gravity.START);
        } else {
            et.setSingleLine(true);
        }
        int pad = dp(ctx, 10);
        et.setPadding(pad, pad, pad, pad);
        et.setBackground(inputBackground(ctx));
        card.addView(et, matchWrap(ctx, 0f));
        return et;
    }

    private static GradientDrawable inputBackground(Context ctx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(ctx, R_INPUT));
        bg.setColor(INPUT_BG);
        bg.setStroke(Math.max(1, dp(ctx, 1)), INPUT_BORDER);
        return bg;
    }

    /** 状态胶囊（参照物用颜色区分状态：绿=正常、黄=待启动、红=异常） */
    static TextView statusChip(Context ctx, String text, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(11f);
        tv.setTextColor(color);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(ctx, 10));
        bg.setColor(withAlpha(color, 0x22));
        tv.setBackground(bg);
        return tv;
    }

    /** 就地改变状态胶囊的语气色，避免每次刷新都新建一个 TextView */
    static void tintChip(TextView chip, int color) {
        if (chip == null) return;
        chip.setTextColor(color);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(chip.getContext(), 10));
        bg.setColor(withAlpha(color, 0x22));
        chip.setBackground(bg);
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
