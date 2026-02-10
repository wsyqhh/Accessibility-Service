package com.orb.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal: expose current AccessibilityNodeInfo tree over HTTP.
 *
 * Endpoints:
 *   GET  /screen
 *     -> JSON { ts, pkg, rev, nodes:[{id,text,desc,viewId,clickable,enabled,bounds:[l,t,r,b]}] }
 *
 *   POST /click?text=XX
 *     -> clicks first node with matching text OR contentDescription (accessibility click)
 *
 *   POST /tap?x=&y=
 *   POST /swipe?x1=&y1=&x2=&y2=&dur=
 *   POST /key?name=home|back|enter|menu
 *     -> Execution layer:
 *        - If root is available: uses `su -c input ...`
 *        - Otherwise: falls back to Accessibility `dispatchGesture` (tap/swipe) and global actions for home/back.
 *
 * Notes:
 * - Server binds 127.0.0.1:7333 (device-local only).
 * - If you change bindHost to 0.0.0.0, add authentication.
 */
class OrbAccessibilityService : AccessibilityService() {

  private val latestRoot = AtomicReference<AccessibilityNodeInfo?>(null)
  private val rev = java.util.concurrent.atomic.AtomicLong(0)

  private var server: ApiServer? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    Log.i(TAG, "Accessibility connected")

    if (server == null) {
      server = ApiServer(
        bindHost = "127.0.0.1",
        port = 7333,
        getRoot = { latestRoot.get() },
        getRev = { rev.get() },
        clickByText = { t -> clickFirstMatch(t) },
        execTap = { x, y -> execTap(x, y) },
        execSwipe = { x1, y1, x2, y2, dur -> execSwipe(x1, y1, x2, y2, dur) },
        execKey = { name -> execKey(name) }
      ).also {
        it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        Log.i(TAG, "HTTP server started on 127.0.0.1:7333")
      }
    }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // Keep a snapshot pointer to current active window.
    val root = rootInActiveWindow
    if (root != null) {
      latestRoot.getAndSet(root)?.recycle()
      rev.incrementAndGet()
    }
  }

  override fun onInterrupt() {
    // no-op
  }

  override fun onDestroy() {
    server?.stop()
    server = null
    latestRoot.getAndSet(null)?.recycle()
    super.onDestroy()
  }

  private fun clickFirstMatch(text: String): Boolean {
    val root = latestRoot.get() ?: return false
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)

    while (queue.isNotEmpty()) {
      val n = queue.removeFirst()
      val t = n.text?.toString()?.trim()
      val d = n.contentDescription?.toString()?.trim()
      if (t == text || d == text) {
        // Walk up to find clickable parent if needed.
        var cur: AccessibilityNodeInfo? = n
        while (cur != null && !cur.isClickable) {
          cur = cur.parent
        }
        val target = cur ?: n
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      }
      for (i in 0 until n.childCount) {
        n.getChild(i)?.let { queue.add(it) }
      }
    }
    return false
  }

  /**
   * Tap:
   * - Root: `input tap x y`
   * - Non-root: dispatchGesture()
   */
  private fun execTap(x: Int, y: Int): Boolean {
    if (Shell.su("input tap $x $y")) return true
    return gestureTap(x, y)
  }

  /**
   * Swipe:
   * - Root: `input swipe x1 y1 x2 y2 dur`
   * - Non-root: dispatchGesture()
   */
  private fun execSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean {
    val dur = durMs.coerceIn(50, 10_000)
    if (Shell.su("input swipe $x1 $y1 $x2 $y2 $dur")) return true
    return gestureSwipe(x1, y1, x2, y2, dur)
  }

  /**
   * Key:
   * - Root: `input keyevent KEYCODE_*`
   * - Non-root: best-effort global actions (home/back/recents)
   */
  private fun execKey(name: String?): Boolean {
    val n = name?.lowercase()?.trim() ?: return false
    val keycode = when (n) {
      "home" -> "KEYCODE_HOME"
      "back" -> "KEYCODE_BACK"
      "enter" -> "KEYCODE_ENTER"
      "menu" -> "KEYCODE_MENU"
      else -> null
    }
    if (keycode != null && Shell.su("input keyevent $keycode")) return true

    // Non-root fallback (limited)
    return when (n) {
      "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
      "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
      "menu" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
      else -> false
    }
  }

  private fun gestureTap(x: Int, y: Int): Boolean {
    return try {
      val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
      val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
        .build()

      val latch = CountDownLatch(1)
      val okRef = AtomicReference(false)

      val ok = dispatchGesture(
        gesture,
        object : GestureResultCallback() {
          override fun onCompleted(gestureDescription: GestureDescription?) {
            okRef.set(true)
            latch.countDown()
          }

          override fun onCancelled(gestureDescription: GestureDescription?) {
            okRef.set(false)
            latch.countDown()
          }
        },
        null
      )
      if (!ok) return false
      latch.await(1500, TimeUnit.MILLISECONDS)
      okRef.get()
    } catch (_: Throwable) {
      false
    }
  }

  private fun gestureSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean {
    return try {
      val path = Path().apply {
        moveTo(x1.toFloat(), y1.toFloat())
        lineTo(x2.toFloat(), y2.toFloat())
      }
      val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0, durMs.toLong()))
        .build()

      val latch = CountDownLatch(1)
      val okRef = AtomicReference(false)

      val ok = dispatchGesture(
        gesture,
        object : GestureResultCallback() {
          override fun onCompleted(gestureDescription: GestureDescription?) {
            okRef.set(true)
            latch.countDown()
          }

          override fun onCancelled(gestureDescription: GestureDescription?) {
            okRef.set(false)
            latch.countDown()
          }
        },
        null
      )
      if (!ok) return false
      latch.await((durMs + 1500).toLong(), TimeUnit.MILLISECONDS)
      okRef.get()
    } catch (_: Throwable) {
      false
    }
  }

  private class ApiServer(
    bindHost: String,
    port: Int,
    val getRoot: () -> AccessibilityNodeInfo?,
    val getRev: () -> Long,
    val clickByText: (String) -> Boolean,
    val execTap: (Int, Int) -> Boolean,
    val execSwipe: (Int, Int, Int, Int, Int) -> Boolean,
    val execKey: (String?) -> Boolean
  ) : NanoHTTPD(bindHost, port) {

    override fun serve(session: IHTTPSession): Response {
      return try {
        when {
          session.method == Method.GET && session.uri == "/screen" -> {
            val root = getRoot()
            val out = JSONObject()
            out.put("ts", System.currentTimeMillis())
            out.put("rev", getRev())
            out.put("pkg", root?.packageName?.toString() ?: JSONObject.NULL)

            val nodes = JSONArray()
            if (root != null) {
              val queue = ArrayDeque<AccessibilityNodeInfo>()
              queue.add(root)
              var id = 0
              while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                val o = JSONObject()
                o.put("id", id++)
                o.put("text", n.text?.toString() ?: JSONObject.NULL)
                o.put("desc", n.contentDescription?.toString() ?: JSONObject.NULL)
                o.put("viewId", n.viewIdResourceName ?: JSONObject.NULL)
                o.put("clickable", n.isClickable)
                o.put("enabled", n.isEnabled)

                val r = android.graphics.Rect()
                n.getBoundsInScreen(r)
                o.put("bounds", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
                nodes.put(o)

                for (i in 0 until n.childCount) {
                  n.getChild(i)?.let { queue.add(it) }
                }
              }
            }
            out.put("nodes", nodes)
            newFixedLengthResponse(Response.Status.OK, "application/json", out.toString())
          }

          session.method == Method.POST && session.uri == "/click" -> {
            val text = session.parameters["text"]?.firstOrNull()?.trim()
            if (text.isNullOrEmpty()) {
              newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing text")
            } else {
              val ok = clickByText(text)
              newFixedLengthResponse(Response.Status.OK, "application/json", JSONObject(mapOf("ok" to ok)).toString())
            }
          }

          session.method == Method.POST && session.uri == "/tap" -> {
            val x = session.parameters["x"]?.firstOrNull()?.toIntOrNull()
            val y = session.parameters["y"]?.firstOrNull()?.toIntOrNull()
            if (x == null || y == null) {
              newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing x/y")
            } else {
              val ok = execTap(x, y)
              newFixedLengthResponse(Response.Status.OK, "application/json", JSONObject(mapOf("ok" to ok)).toString())
            }
          }

          session.method == Method.POST && session.uri == "/swipe" -> {
            fun i(name: String) = session.parameters[name]?.firstOrNull()?.toIntOrNull()
            val x1 = i("x1"); val y1 = i("y1"); val x2 = i("x2"); val y2 = i("y2")
            val dur = i("dur") ?: 300
            if (x1 == null || y1 == null || x2 == null || y2 == null) {
              newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing x1/y1/x2/y2")
            } else {
              val ok = execSwipe(x1, y1, x2, y2, dur)
              newFixedLengthResponse(Response.Status.OK, "application/json", JSONObject(mapOf("ok" to ok)).toString())
            }
          }

          session.method == Method.POST && session.uri == "/key" -> {
            val name = session.parameters["name"]?.firstOrNull()
            val ok = execKey(name)
            newFixedLengthResponse(Response.Status.OK, "application/json", JSONObject(mapOf("ok" to ok)).toString())
          }

          else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
      } catch (e: Throwable) {
        Log.e(TAG, "serve error", e)
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}")
      }
    }
  }

  companion object {
    private const val TAG = "OrbA11y"
  }
}

private object Shell {
  /** Run a command through `su -c`. Returns true on exit code 0. */
  fun su(cmd: String): Boolean {
    return try {
      val p = ProcessBuilder("su", "-c", cmd)
        .redirectErrorStream(true)
        .start()
      val code = p.waitFor()
      code == 0
    } catch (_: Throwable) {
      false
    }
  }
}
