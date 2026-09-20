package com.daka.auto;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.ScrollView;

/**
 * 日志滚动容器：嵌套在外层页面 ScrollView 中时，外层会优先抢占竖向滑动
 * 导致日志区划不动。这里在手指按下时即声明独占手势（禁止父级拦截），
 * 保证日志区可以独立滚动；在日志区之外滑动仍正常滚动页面。
 */
public class LogScrollView extends ScrollView {

    public LogScrollView(Context context) {
        super(context);
    }

    public LogScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LogScrollView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            ViewParent p = getParent();
            if (p != null) {
                p.requestDisallowInterceptTouchEvent(true);
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            ViewParent p = getParent();
            if (p != null) {
                p.requestDisallowInterceptTouchEvent(true);
            }
        }
        return super.onTouchEvent(ev);
    }
}
