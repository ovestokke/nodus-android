package org.qosp.notes.ui.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.StrikethroughSpan
import android.text.style.URLSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.mockk.mockk
import io.mockk.verify
import io.noties.markwon.Markwon
import me.saket.bettermovementmethod.BetterLinkMovementMethod
import org.json.JSONArray
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.R
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteTask
import org.qosp.notes.databinding.LayoutNoteBinding
import org.qosp.notes.databinding.LayoutTaskBinding
import org.qosp.notes.di.MarkwonModule
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.ui.common.recycler.NoteRecyclerListener
import org.qosp.notes.ui.common.recycler.NoteViewHolder
import org.qosp.notes.ui.tasks.TaskRecyclerListener
import org.qosp.notes.ui.tasks.TaskViewHolder
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HttpLinksTest {
    @Test fun sharedFixturesPreserveAllTextAndExactLinkLabelsInActiveAndCompletedDisplay() {
        // Shared contract v1: nodus/web/tests/fixtures/client-link-fixtures.json (18 unchanged cases).
        val fixtures = JSONArray(javaClass.getResource("/client-link-fixtures.json")!!.readText())
        assertEquals(18, fixtures.length())
        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index)
            val name = fixture.getString("name")
            val text = fixture.getString("text")
            val expected = fixture.getJSONArray("links").let { values ->
                (0 until values.length()).map { values.getString(it) }
            }
            val links = HttpLinks.find(text)
            assertEquals(name, expected, links.map { it.destination })
            assertEquals(name, expected, links.map { text.substring(it.start, it.end) })
            var offset = 0
            val rebuilt = buildString {
                links.forEach {
                    append(text.substring(offset, it.start))
                    append(it.destination)
                    offset = it.end
                }
                append(text.substring(offset))
            }
            assertEquals(name, text, rebuilt)
            for (completed in listOf(false, true)) {
                val view = TextView(RuntimeEnvironment.getApplication())
                view.setHttpLinkText(text, completed)
                assertEquals(name, text, view.text.toString())
                val display = view.text as Spanned
                val spans = display.getSpans(0, display.length, URLSpan::class.java)
                assertEquals(name, expected, spans.map { it.url })
                assertEquals(name, expected, spans.map {
                    text.substring(display.getSpanStart(it), display.getSpanEnd(it))
                })
                assertEquals(if (completed) 1 else 0,
                    display.getSpans(0, display.length, StrikethroughSpan::class.java).size)
            }
        }
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun openerRechecksSchemesAuthoritiesAndHandlesMissingBrowserWithoutNetwork() {
        val app = RuntimeEnvironment.getApplication()
        for (url in listOf("javascript:https://example.com", "file:///tmp/a", "https://u:p@example.com",
            "https://example.com:0", "https://example.com/\u2066x", "https://")) {
            assertFalse(url, openHttpLink(app, url))
            assertNull(shadowOf(app).nextStartedActivity)
        }
        val unicode = "https://例子.测试/路径?q=blå%20b"
        assertTrue(openHttpLink(app, unicode))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(unicode, intent.data.toString())
        assertTrue(openHttpLink(app, "HTTP://localhost:8080/a"))
        assertEquals("http://localhost:8080/a", shadowOf(app).nextStartedActivity.data.toString())
        val noBrowser = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        assertFalse(openHttpLink(noBrowser, "https://example.com"))
    }

    @Test fun cardLinkOpensWithoutOpeningEditorWhileOrdinaryTapStillOpensNote() {
        val activity = activity()
        val binding = LayoutNoteBinding.inflate(LayoutInflater.from(activity))
        val listener = mockk<NoteRecyclerListener>(relaxed = true)
        val holder = NoteViewHolder(binding, listener, activity, false, mockk(),
            RecyclerView.RecycledViewPool(), RecyclerView.RecycledViewPool())
        val raw = "plain https://example.com/path end"
        val note = Note(title = "Note", content = raw, isMarkdownEnabled = false)
        holder.bind(note)
        show(activity, binding.root)
        tap(binding.root, binding.textViewContent)
        assertOpened("https://example.com/path")
        verify(exactly = 0) { listener.onItemClick(any(), any()) }
        verify(exactly = 0) { listener.onLongClick(any(), any()) }
        assertEquals(raw, note.content)
        tapAt(binding.root, 2f, 2f)
        verify(exactly = 1) { listener.onItemClick(any(), any()) }
    }

    @Test fun plainToMarkdownRecycleDelegatesOnlySafeHttpLinksAndPreservesOrdinaryCardActions() {
        val activity = activity()
        val koin = koinApplication {
            androidContext(activity)
            modules(MarkwonModule.markwonModule, module { single<PreferenceRepository> { mockk() } })
        }
        try {
            val markwon = koin.koin.get<Markwon>()
            val binding = LayoutNoteBinding.inflate(LayoutInflater.from(activity))
            val listener = mockk<NoteRecyclerListener>(relaxed = true)
            val holder = NoteViewHolder(binding, listener, activity, false, markwon,
                RecyclerView.RecycledViewPool(), RecyclerView.RecycledViewPool())
            holder.bind(Note(content = "https://example.com/plain", isMarkdownEnabled = false))
            val plainMovement = binding.textViewContent.movementMethod
            holder.bind(Note(content = "ordinary [Example](https://example.com/md)", isMarkdownEnabled = true))
            show(activity, binding.root)
            val view = binding.textViewContent
            assertNotSame(plainMovement, view.movementMethod)
            assertSame(BetterLinkMovementMethod.getInstance(), view.movementMethod)
            assertEquals("ordinary Example", view.text.toString().trim())
            val display = view.text as Spanned
            assertEquals(0, display.getSpans(0, display.length, HttpUrlSpan::class.java).size)
            assertEquals(1, display.getSpans(0, display.length, ClickableSpan::class.java).size)
            tap(binding.root, view, httpLink = false)
            assertOpened("https://example.com/md")
            verify(exactly = 0) { listener.onItemClick(any(), any()) }
            verify(exactly = 0) { listener.onLongClick(any(), any()) }
            val plainLine = view.layout.getLineForOffset(2)
            val ordinaryX = view.layout.getPrimaryHorizontal(2) + view.totalPaddingLeft
            val ordinaryY = (view.layout.getLineTop(plainLine) + view.layout.getLineBottom(plainLine)) / 2f + view.totalPaddingTop
            val rootLocation = IntArray(2).also(binding.root::getLocationOnScreen)
            val textLocation = IntArray(2).also(view::getLocationOnScreen)
            tapAt(binding.root, ordinaryX + textLocation[0] - rootLocation[0],
                ordinaryY + textLocation[1] - rootLocation[1])
            verify(exactly = 1) { listener.onItemClick(any(), any()) }

            // The existing Markwon LinkifyPlugin's ordinary URLSpan also delegates safely.
            holder.bind(Note(content = "https://example.com/auto", isMarkdownEnabled = true))
            show(activity, binding.root)
            tap(binding.root, view, httpLink = false)
            assertOpened("https://example.com/auto")
            verify(exactly = 1) { listener.onItemClick(any(), any()) }

            val unsupported = listOf("mailto:user@example.com", "/relative", "javascript:alert(1)",
                "https://user:pass@example.com/a", "https://example.com:0/a")
            unsupported.forEachIndexed { index, destination ->
                holder.bind(Note(content = "[Target]($destination)", isMarkdownEnabled = true))
                show(activity, binding.root)
                val (x, y) = linkPoint(view)
                assertNull(destination, view.safeCardHttpLinkAt(x, y))
                tap(binding.root, view, httpLink = false)
                assertNull(destination, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
                verify(exactly = index + 2) { listener.onItemClick(any(), any()) }
            }

            // Even a URLSpan subclass with a safe-looking destination may define an arbitrary action.
            var arbitraryClicks = 0
            val custom = android.text.SpannableString("Custom").apply {
                setSpan(object : URLSpan("https://example.com/custom") {
                    override fun onClick(widget: View) { arbitraryClicks++ }
                }, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            view.text = custom
            show(activity, binding.root)
            val (customX, customY) = linkPoint(view)
            assertNull(view.safeCardHttpLinkAt(customX, customY))
            tap(binding.root, view, httpLink = false)
            assertEquals(0, arbitraryClicks)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)

            holder.bind(Note(content = "https://example.com/plain", isMarkdownEnabled = false))
            show(activity, binding.root)
            assertNotSame(BetterLinkMovementMethod.getInstance(), view.movementMethod)
            tap(binding.root, view)
            assertOpened("https://example.com/plain")
        } finally {
            koin.close()
        }
    }

    @Test fun cardChecklistLinksInActiveAndCompletedRowsDoNotOpenEditor() {
        for (completed in listOf(false, true)) {
            val activity = activity()
            val binding = LayoutNoteBinding.inflate(LayoutInflater.from(activity))
            val listener = mockk<NoteRecyclerListener>(relaxed = true)
            val holder = NoteViewHolder(binding, listener, activity, false, mockk(),
                RecyclerView.RecycledViewPool(), RecyclerView.RecycledViewPool())
            holder.bind(Note(title = "List", isList = true, taskList = listOf(
                NoteTask(1, "https://example.com/task", completed))))
            show(activity, binding.root)
            val row = binding.recyclerTasks.getChildAt(0)
            assertNotNull(row)
            val text = row.findViewById<TextView>(R.id.text_view)
            assertTrue(text.isEnabled)
            tap(binding.root, text)
            assertOpened("https://example.com/task")
            verify(exactly = 0) { listener.onItemClick(any(), any()) }
        }
    }

    @Test fun checklistReadLinksNeverToggleOrEditAndEditFieldRemainsRaw() {
        for (completed in listOf(false, true)) {
            val activity = activity()
            val binding = LayoutTaskBinding.inflate(LayoutInflater.from(activity))
            val listener = mockk<TaskRecyclerListener>(relaxed = true)
            val holder = TaskViewHolder(activity, binding, listener, false)
            val raw = "  **raw** https://example.com/task\nnext  "
            holder.bind(NoteTask(1, raw, completed))
            holder.isEnabled = false
            show(activity, binding.root)
            assertEquals(raw, binding.textView.text.toString())
            assertEquals(raw, binding.editText.text.toString())
            assertEquals(0, binding.editText.text!!.getSpans(0, raw.length, URLSpan::class.java).size)
            assertTrue(binding.textView.isEnabled)
            val expectedColor = if (completed) binding.editText.textColors.getColorForState(
                intArrayOf(-android.R.attr.state_enabled), binding.editText.textColors.defaultColor
            ) else binding.editText.textColors.defaultColor
            assertEquals(expectedColor, binding.textView.currentTextColor)
            tap(binding.root, binding.textView)
            assertOpened("https://example.com/task")
            assertEquals(completed, binding.checkBox.isChecked)
            verify(exactly = 0) { listener.onTaskStatusChanged(any(), any()) }
            verify(exactly = 0) { listener.onTaskContentChanged(any(), any()) }
            binding.checkBox.performClick()
            verify(exactly = 1) { listener.onTaskStatusChanged(any(), !completed) }
            // Restore the same state for checking the existing edit/read visibility.
            holder.bind(NoteTask(1, raw, completed))
            if (!completed) {
                holder.isEnabled = true
                assertEquals(View.VISIBLE, binding.editText.visibility)
                assertEquals(View.GONE, binding.textView.visibility)
            }
        }
    }

    @Test fun keyboardActivationWorksAndCancelledOrDraggedTouchesDoNotOpen() {
        val activity = activity()
        val view = TextView(activity).apply {
            setTextIsSelectable(true)
            setHttpLinkText("https://example.com/a")
        }
        show(activity, view)
        assertTrue(view.isTextSelectable)
        val display = view.text as Spannable
        Selection.setSelection(display, 0, display.length)
        assertTrue(view.movementMethod.onKeyDown(view, display, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))
        assertOpened("https://example.com/a")
        assertTrue(view.movementMethod.onKeyUp(view, display, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)))
        val (x, y) = linkPoint(view)
        touch(view, MotionEvent.ACTION_DOWN, x, y)
        touch(view, MotionEvent.ACTION_CANCEL, x, y)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        touch(view, MotionEvent.ACTION_DOWN, x, y)
        touch(view, MotionEvent.ACTION_MOVE, x + 100f, y)
        touch(view, MotionEvent.ACTION_UP, x, y)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        touch(view, MotionEvent.ACTION_DOWN, x, y)
        touch(view, MotionEvent.ACTION_UP, x, y, eventTime = 1000)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun blankSpaceAndEllipsisDoNotActivateNearestLinkAndRebindingDropsOldSpans() {
        val activity = activity()
        val view = TextView(activity).apply {
            setTextIsSelectable(true)
            setHttpLinkText("https://example.com/a")
        }
        show(activity, view)
        val (_, y) = linkPoint(view)
        tapAt(view, 900f, y)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        view.maxLines = 1
        view.setHttpLinkText("https://example.com/" + "long".repeat(200))
        view.ellipsize() // Exercise the app's existing manual preview truncation.
        show(activity, view)
        view.viewTreeObserver.dispatchOnGlobalLayout()
        assertTrue(view.text.toString().endsWith("..."))
        show(activity, view)
        val x = view.layout.getPrimaryHorizontal(view.text.length - 2) + 1f
        assertNull(view.httpLinkAt(x, y))
        tapAt(view, x, y)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        view.setHttpLinkText("ordinary text")
        val display = view.text as Spanned
        assertEquals(0, display.getSpans(0, display.length, URLSpan::class.java).size)
    }

    @Test fun malformedHostsPortsAndEmbeddedSchemesStayPlain() {
        for (raw in listOf("https://.../a", "https://-/a", "https://example.com:000080/a",
            "javascript:https://example.com", "https://bad,http://example.com", "\u001Chttps://example.com")) {
            assertTrue(raw, HttpLinks.find(raw).isEmpty())
        }
        assertEquals(listOf("https://example.com"), HttpLinks.find("\u00A0https://example.com").map { it.destination })
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(R.style.AppTheme)
    }

    private fun show(activity: Activity, view: View) {
        activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1000, 800)
    }

    private fun linkPoint(view: TextView): Pair<Float, Float> {
        val display = view.text as Spanned
        val span = display.getSpans(0, display.length, ClickableSpan::class.java).first()
        val offset = display.getSpanStart(span) + 3
        val line = view.layout.getLineForOffset(offset)
        return (view.layout.getPrimaryHorizontal(offset) + view.totalPaddingLeft - view.scrollX) to
            ((view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f + view.totalPaddingTop - view.scrollY)
    }

    private fun tap(root: View, text: TextView, httpLink: Boolean = true) {
        val rootLocation = IntArray(2).also(root::getLocationOnScreen)
        val textLocation = IntArray(2).also(text::getLocationOnScreen)
        val (x, y) = linkPoint(text)
        if (httpLink) assertNotNull("Expected a direct HTTP span hit at ($x, $y)", text.httpLinkAt(x, y))
        tapAt(root, x + textLocation[0] - rootLocation[0], y + textLocation[1] - rootLocation[1])
    }

    private fun tapAt(root: View, x: Float, y: Float) {
        touch(root, MotionEvent.ACTION_DOWN, x, y)
        touch(root, MotionEvent.ACTION_UP, x, y)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun touch(root: View, action: Int, x: Float, y: Float, eventTime: Long = 10) {
        val event = MotionEvent.obtain(0, eventTime, action, x, y, 0)
        root.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun assertOpened(url: String) {
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull("Expected external browser activation", intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.data.toString())
    }
}
