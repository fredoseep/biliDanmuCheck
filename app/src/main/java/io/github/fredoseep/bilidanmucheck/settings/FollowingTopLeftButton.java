package io.github.fredoseep.bilidanmucheck.settings;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Adds the following-feed filter settings button to ExhibitionFragment. */
public final class FollowingTopLeftButton {
    private static final String TAG = "FollowingTopLeft";
    private static final String BUTTON_TAG = "fredoseep.following.settings.v1";
    private static final Set<Class<?>> HOOKED =
            Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());

    public static void hook(ClassLoader classLoader) {
        try {
            Class<?> fragment = XposedHelpers.findClass(
                    "com.bilibili.bplus.following.home.ui.exhibition.ExhibitionFragment", classLoader);
            synchronized (HOOKED) {
                if (HOOKED.contains(fragment)) return;
                XposedHelpers.findAndHookMethod(fragment, "onCreateView",
                        LayoutInflater.class, ViewGroup.class, Bundle.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Log.d(TAG, "[Entry] onCreateView invoked, fragment="
                                        + param.thisObject.getClass().getName()
                                        + ", pid=" + android.os.Process.myPid()
                                        + ", error=" + param.hasThrowable());
                                if (param.hasThrowable()) {
                                    Log.d(TAG, "[Entry] original failed: " + Log.getStackTraceString(param.getThrowable()));
                                    return;
                                }
                                if (!(param.getResult() instanceof View)) {
                                    Log.d(TAG, "[Entry] returned no View");
                                    return;
                                }
                                inspectAndAdd((View) param.getResult(), "ExhibitionFragment.onCreateView");
                            }
                        });
                HOOKED.add(fragment);
            }
            Log.d(TAG, "ExhibitionFragment.onCreateView hook installed");
        } catch (Throwable t) {
            Log.d(TAG, "Hook installation failed: " + Log.getStackTraceString(t));
        }
        installFragmentProbe(classLoader);
    }

    /** AndroidX 创建视图的公共生命周期入口，覆盖平板上不同的关注页 Fragment。 */
    private static void installFragmentProbe(ClassLoader loader) {
        try {
            Class<?> fragment = XposedHelpers.findClass("androidx.fragment.app.Fragment", loader);
            synchronized (HOOKED) {
                if (HOOKED.contains(fragment)) return;
                XposedHelpers.findAndHookMethod(fragment, "performCreateView",
                        LayoutInflater.class, ViewGroup.class, Bundle.class, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                String name = param.thisObject.getClass().getName();
                                if (!name.contains(".following.") && !name.contains(".dynamic.")) return;
                                Log.d(TAG, "[FragmentProbe] created=" + name + ", error=" + param.hasThrowable());
                                if (param.hasThrowable()) return;
                                try {
                                    Object view = XposedHelpers.callMethod(param.thisObject, "getView");
                                    if (view instanceof View) inspectAndAdd((View) view, name);
                                    else Log.d(TAG, "[FragmentProbe] no view: " + name);
                                } catch (Throwable error) {
                                    Log.d(TAG, "[FragmentProbe] inspect failed: " + error);
                                }
                            }
                        });
                HOOKED.add(fragment);
            }
            Log.d(TAG, "[FragmentProbe] performCreateView hook installed");
        } catch (Throwable error) {
            Log.d(TAG, "[FragmentProbe] installation failed: " + error);
        }
    }

    private static void inspectAndAdd(View root, String source) {
        try {
            Log.d(TAG, "[Root] source=" + source + ", contextPackage=" + root.getContext().getPackageName()
                    + ", resource=" + resourceName(root));
            addButton(root);
        } catch (Throwable error) {
            Log.d(TAG, "Failed to add settings button: " + Log.getStackTraceString(error));
        }
    }

    private static void addButton(View root) {
        if (root.findViewWithTag(BUTTON_TAG) != null) return;
        // Numeric fallbacks correspond to the supplied fbC.xml, not other builds.
        View toolbar = find(root, "nav_top_bar", 0x7f0921f0);
        View tabs = find(root, "toolbar_tabs", 0x7f094791);
        View title = find(root, "title", 0x7f09319e);
        View publish = find(root, "fo_publish_menu", 0x7f091787);
        if (!(toolbar instanceof ViewGroup)
                || !toolbar.getClass().getName().endsWith("Toolbar")
                || tabs == null || !tabs.getClass().getName().endsWith("DynamicTabStrip")
                || !(tabs.getParent() instanceof ViewGroup)) {
            Log.d(TAG, "[LayoutMismatch] toolbar=" + describe(toolbar) + ", tabs=" + describe(tabs));
            dumpToolbarTree(root);
            return;
        }
        ViewGroup container = (ViewGroup) tabs.getParent();
        if (!inside(container, toolbar) || publish == null || publish.getParent() != container) {
            Log.d(TAG, "[LayoutMismatch] child hierarchy differs: container=" + describe(container)
                    + ", publish=" + describe(publish));
            dumpToolbarTree(toolbar);
            return;
        }
        ViewGroup.LayoutParams existing = tabs.getLayoutParams();
        if (existing == null || !existing.getClass().getName().contains("constraintlayout.widget.ConstraintLayout$LayoutParams")) {
            Log.d(TAG, "[LayoutMismatch] expected ConstraintLayout.LayoutParams, got "
                    + (existing == null ? "null" : existing.getClass().getName()));
            return;
        }

        Context context = root.getContext();
        int size = dp(context, 48);
        ViewGroup.LayoutParams buttonParams = (ViewGroup.LayoutParams) XposedHelpers.newInstance(
                existing.getClass(), size, size);
        XposedHelpers.setIntField(buttonParams, "startToStart", 0);
        XposedHelpers.setIntField(buttonParams, "topToTop", 0);
        XposedHelpers.setIntField(buttonParams, "bottomToBottom", 0);

        TextView button = new TextView(context);
        button.setId(View.generateViewId());
        button.setTag(BUTTON_TAG);
        button.setText("设置");
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription("关注页设置");
        button.setClickable(true);
        button.setFocusable(true);
        if (title instanceof TextView) button.setTextColor(((TextView) title).getTextColors());
        TypedValue background = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true)
                && background.resourceId != 0) {
            button.setBackgroundResource(background.resourceId);
        }
        if (Build.VERSION.SDK_INT >= 26) button.setTooltipText("关注页设置");
        button.setOnClickListener(v -> showSettings(v.getContext()));

        // Keep the centered tab strip/title clear of both edge buttons.
        reserveSpace(tabs, size);
        if (title != null && title.getParent() == container) reserveSpace(title, size);
        container.addView(button, buttonParams);
        publish.post(() -> {
            // A wider publish action may require additional symmetric space.
            int inset = Math.max(size, publish.getWidth());
            reserveSpace(tabs, inset);
            if (title != null && title.getParent() == container) reserveSpace(title, inset);
        });
        Log.d(TAG, "[Added] Settings button added to " + container.getClass().getName());
        button.post(() -> Log.d(TAG, "[ButtonLayout] shown=" + button.isShown()
                + ", visibility=" + button.getVisibility() + ", width=" + button.getWidth()
                + ", height=" + button.getHeight()));
    }

    private static void reserveSpace(View view, int inset) {
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) raw;
        params.setMarginStart(Math.max(params.getMarginStart(), inset));
        params.setMarginEnd(Math.max(params.getMarginEnd(), inset));
        // ConstraintLayout wrap_content must respect its two side constraints.
        try {
            XposedHelpers.setBooleanField(params, "constrainedWidth", true);
        } catch (NoSuchFieldError ignored) {
            Log.d(TAG, "constrainedWidth unavailable in host ConstraintLayout");
        }
        view.setLayoutParams(params);
    }

    private static boolean inside(View view, View ancestor) {
        View current = view;
        while (current != null) {
            if (current == ancestor) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    private static View find(View root, String name, int fallbackId) {
        Context context = root.getContext();
        java.util.LinkedHashSet<String> packages = new java.util.LinkedHashSet<>();
        packages.add(context.getPackageName());
        try { packages.add(root.getResources().getResourcePackageName(root.getId())); }
        catch (android.content.res.Resources.NotFoundException ignored) { }
        // LSPatch 改 applicationId 后，resources.arsc 的包名可能仍然是原包名。
        packages.add("tv.danmaku.bili");
        packages.add("tv.danmaku.bili.hd");
        packages.add("com.bilibili.app.in");
        for (String pkg : packages) {
            int id = context.getResources().getIdentifier(name, "id", pkg);
            View result = id == 0 ? null : root.findViewById(id);
            if (result != null) {
                Log.d(TAG, "[Find] " + name + " via " + pkg + " -> " + describe(result));
                return result;
            }
        }
        // 直接从现有视图的资源条目名查找，覆盖重打包后的其他资源包名。
        View byName = findByEntryName(root, name, new int[]{0});
        if (byName != null) {
            Log.d(TAG, "[Find] " + name + " via view tree -> " + describe(byName));
            return byName;
        }
        // 保留原来的数字回退；后面的类型、父子关系检查会验证布局是否匹配。
        View fallback = root.findViewById(fallbackId);
        Log.d(TAG, "[Find] " + name + " fallback -> " + describe(fallback));
        return fallback;
    }

    private static View findByEntryName(View view, String name, int[] visited) {
        if (++visited[0] > 2000) return null;
        if (view.getId() != View.NO_ID) {
            try {
                if (name.equals(view.getResources().getResourceEntryName(view.getId()))) return view;
            } catch (android.content.res.Resources.NotFoundException ignored) { }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByEntryName(group.getChildAt(i), name, visited);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String resourceName(View view) {
        try { return view.getResources().getResourceName(view.getId()); }
        catch (android.content.res.Resources.NotFoundException ignored) {
            return "0x" + Integer.toHexString(view.getId());
        }
    }

    private static String describe(View view) {
        return view == null ? "null" : view.getClass().getName() + "[" + resourceName(view) + "]";
    }

    /** 只输出类型和资源名，不读取关注页正文。 */
    private static void dumpToolbarTree(View root) {
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int count = 0;
        while (!queue.isEmpty() && count++ < 100) {
            View view = queue.removeFirst();
            Log.d(TAG, "[ViewTree] " + describe(view) + ", visible=" + view.getVisibility());
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void showSettings(Context context) {
        FollowingFilterSettings.show(context);
    }
}
