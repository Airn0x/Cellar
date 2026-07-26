package app.cellar

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * A WebView that behaves like a terminal instead of a web page.
 *
 * Two Android-specific problems this solves:
 *
 * 1. **The IME thinks it's editing prose.** Without these flags a soft
 *    keyboard offers spell-check, autocorrect and a suggestion strip, and
 *    in landscape it may take over the screen with "extract mode". The
 *    visible-password input type is the long-standing terminal-app trick
 *    for turning all of that off.
 *
 * 2. **A scrolling parent steals the touches.** Inside a scrollable
 *    layout, drags never reach the terminal — which reads as "touch
 *    doesn't work". The view claims its gestures while a finger is down.
 */
@SuppressLint("ViewConstructor")
class TerminalWebView(context: Context) : WebView(context) {

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = outAttrs.imeOptions or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_ACTION_NONE
        return ic
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(event)
    }
}
