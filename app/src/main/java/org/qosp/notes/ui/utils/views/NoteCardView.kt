package org.qosp.notes.ui.utils.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import org.qosp.notes.ui.utils.safeCardHttpLinkAt

class NoteCardView : MaterialCardView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private var childLinkGesture = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            childLinkGesture = hasHttpLinkAt(this, ev.x, ev.y)
        }
        // Ordinary card taps/long presses still belong to the card, not nested rows.
        return !childLinkGesture
    }

    private fun hasHttpLinkAt(view: View, x: Float, y: Float): Boolean {
        if (!view.isShown || x < 0 || y < 0 || x >= view.width || y >= view.height) return false
        if (view is TextView && view.safeCardHttpLinkAt(x, y) != null) return true
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(index)
                if (hasHttpLinkAt(child, x + view.scrollX - child.x, y + view.scrollY - child.y)) return true
            }
        }
        return false
    }
}
