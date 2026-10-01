package org.qosp.notes.ui.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Selection
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.method.ScrollingMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StrikethroughSpan
import android.text.style.URLSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import io.noties.markwon.core.spans.LinkSpan

/** Only used on read surfaces. Editable fields keep their original raw text. */
fun TextView.setHttpLinkText(content: String, completed: Boolean = false) {
    val display = SpannableString(content)
    if (completed) display.setSpan(StrikethroughSpan(), 0, display.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    HttpLinks.find(content).forEach { link ->
        display.setSpan(HttpUrlSpan(link.destination), link.start, link.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    movementMethod = HttpLinkMovementMethod()
    text = display
}

internal class HttpUrlSpan(destination: String) : URLSpan(destination) {
    override fun onClick(widget: View) {
        openHttpLink(widget.context, url)
    }
}

/** Revalidate at the native launch boundary, even if a span was constructed elsewhere. */
internal fun openHttpLink(context: Context, destination: String): Boolean {
    if (!HttpLinks.isValidDestination(destination)) return false
    // Android intent filters match schemes case-sensitively; this is transport-only.
    val uri = Uri.parse(destination).normalizeScheme()
    if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** Bounds matter: getOffsetForHorizontal alone also hits links from blank space beside a line. */
internal fun TextView.httpLinkAt(x: Float, y: Float): HttpUrlSpan? = clickableSpanAt(x, y) as? HttpUrlSpan

/** Delegate only known native/Markwon HTTP destinations, never arbitrary clickable-span actions. */
internal fun TextView.safeCardHttpLinkAt(x: Float, y: Float): ClickableSpan? {
    val span = clickableSpanAt(x, y) ?: return null
    val destination = when {
        span is HttpUrlSpan -> span.url
        span.javaClass == LinkSpan::class.java -> (span as LinkSpan).link
        span.javaClass == URLSpan::class.java -> (span as URLSpan).url
        else -> return null
    }
    return span.takeIf { HttpLinks.isValidDestination(destination) }
}

private fun TextView.clickableSpanAt(x: Float, y: Float): ClickableSpan? {
    val display = text as? Spanned ?: return null
    val textLayout = layout ?: return null
    val localX = x - totalPaddingLeft + scrollX
    val localY = y - totalPaddingTop + scrollY
    if (localY < 0 || localY >= textLayout.height) return null
    val line = textLayout.getLineForVertical(localY.toInt())
    if (line >= maxLines || localX < textLayout.getLineLeft(line) || localX >= textLayout.getLineRight(line)) return null
    val offset = textLayout.getOffsetForHorizontal(line, localX)
    // Do not activate truncated text through its ellipsis.
    if (textLayout.getEllipsisCount(line) > 0 && offset >=
        textLayout.getLineStart(line) + textLayout.getEllipsisStart(line)) return null
    return display.getSpans(offset, offset, ClickableSpan::class.java)
        .firstOrNull { offset >= display.getSpanStart(it) && offset < display.getSpanEnd(it) }
}

/** Consume a link gesture without forwarding it to note-card/editor/checkbox actions. */
private class HttpLinkMovementMethod : LinkMovementMethod() {
    private var tracking = false
    private var pressed: HttpUrlSpan? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var keyboardActivated = false

    override fun onKeyDown(widget: TextView, buffer: Spannable, keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) &&
            event.hasNoModifiers() && event.repeatCount == 0
        ) {
            val start = Selection.getSelectionStart(buffer)
            val end = Selection.getSelectionEnd(buffer)
            if (start >= 0 && end >= 0 && start != end) {
                val links = buffer.getSpans(minOf(start, end), maxOf(start, end), HttpUrlSpan::class.java)
                if (links.size == 1) {
                    links[0].onClick(widget)
                    keyboardActivated = true
                    return true
                }
            }
        }
        return super.onKeyDown(widget, buffer, keyCode, event)
    }

    override fun onKeyUp(widget: TextView, buffer: Spannable, keyCode: Int, event: KeyEvent): Boolean {
        if (keyboardActivated && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
            keyboardActivated = false
            return true
        }
        return super.onKeyUp(widget, buffer, keyCode, event)
    }

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pressed = widget.httpLinkAt(event.x, event.y)
            tracking = pressed != null
            downX = event.x
            downY = event.y
            downTime = event.eventTime
            pressed?.let { Selection.setSelection(buffer, buffer.getSpanStart(it), buffer.getSpanEnd(it)) }
        }
        // Keep ordinary selection/scrolling, without LinkMovementMethod's nearest-offset link hit.
        if (!tracking) return ScrollingMovementMethod.getInstance().onTouchEvent(widget, buffer, event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(widget.context).scaledTouchSlop
                if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) {
                    pressed = null
                    Selection.removeSelection(buffer)
                }
            }
            MotionEvent.ACTION_UP -> {
                val link = pressed
                tracking = false
                pressed = null
                Selection.removeSelection(buffer)
                if (link != null && event.eventTime - downTime < ViewConfiguration.getLongPressTimeout() &&
                    widget.httpLinkAt(event.x, event.y) === link
                ) link.onClick(widget)
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                pressed = null
                Selection.removeSelection(buffer)
            }
        }
        return true
    }
}
