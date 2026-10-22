package audit

import android.view.*
import android.widget.*
import io.github.hyperisland.xposed.hook.MarqueeHook

fun result(name: String, expected: Int, actual: Int) {
    println("""{"case":"$name","expected":$expected,"actual":$actual,"passed":${actual == expected}}""")
}

fun main(args: Array<String>) {
    if ("--negative-control" in args) { pauseCounterCases(); return }
    val scheduler = Choreographer.getInstance()
    val window = ViewGroup()
    val root = ViewGroup().apply { width = 400; parent = window }
    val view = TextView().apply { parent = root }
    root.children.add(view)
    MarqueeHook.islandMarqueeState[root] = true
    val controller = MarqueeHook.MarqueeController(view)
    controller.start(1000f)
    scheduler.frame(1_000_000_000L)
    result("initial_visible_offset_unchanged", 0, view.scrollX)
    scheduler.frame(2_500_000_000L)
    scheduler.frame(3_500_000_000L)
    result("visible_speed_unchanged_100px_per_second", 100, view.scrollX)
    scheduler.frame(8_500_000_000L)
    result("visible_endpoint_unchanged", 600, view.scrollX)
    scheduler.frame(9_500_000_000L)
    result("visible_end_pause_unchanged_1000ms", 600, view.scrollX)
    scheduler.frame(9_501_000_000L)
    result("visible_next_loop_returns_to_start", 0, view.scrollX)
    result("visible_long_text_has_one_frame", 1, scheduler.pending.size)

    var time = 10_000_000_000L
    fun hidden(name: String, hide: () -> Unit, show: () -> Unit) {
        hide()
        view.viewTreeObserver.draw() // Native visibility traversal; does not request a frame.
        time += 100_000_000L
        scheduler.frame(time)
        result("${name}_pauses_frames", 0, scheduler.pending.size)
        result("${name}_installs_one_listener", 1, view.viewTreeObserver.listeners.size)
        repeat(100) { controller.start(1000f); view.viewTreeObserver.draw() }
        result("${name}_updates_and_draws_keep_frames_paused", 0, scheduler.pending.size)
        result("${name}_updates_do_not_duplicate_listener", 1, view.viewTreeObserver.listeners.size)
        show()
        view.viewTreeObserver.draw()
        view.viewTreeObserver.draw()
        result("${name}_visible_resumes_one_frame", 1, scheduler.pending.size)
        result("${name}_visible_removes_listener", 0, view.viewTreeObserver.listeners.size)
        time += 100_000_000L
        scheduler.frame(time)
    }
    hidden("self_invisible", { view.visibility = View.INVISIBLE }, { view.visibility = View.VISIBLE })
    hidden("ancestor_invisible", { root.visibility = View.INVISIBLE }, { root.visibility = View.VISIBLE })
    hidden("ancestor_gone", { root.visibility = View.GONE }, { root.visibility = View.VISIBLE })
    hidden("outside_island_ancestor_hidden", { window.visibility = View.INVISIBLE }, { window.visibility = View.VISIBLE })
    hidden("self_alpha_zero", { view.alpha = 0f }, { view.alpha = 1f })
    hidden("ancestor_alpha_zero", { window.alpha = 0f }, { window.alpha = 1f })
    hidden("window_hidden", { view.windowVisibility = View.GONE }, { view.windowVisibility = View.VISIBLE })
    root.alpha = 0.01f
    scheduler.frame(++time)
    result("partially_visible_keeps_scrolling", 1, scheduler.pending.size)
    root.alpha = 1f
    root.width = 1200
    view.viewTreeObserver.draw()
    scheduler.frame(++time)
    result("fitting_text_stops_frames", 0, scheduler.pending.size)
    result("fitting_text_resets_offset", 0, view.scrollX)
    result("fitting_text_has_one_listener", 1, view.viewTreeObserver.listeners.size)
    repeat(100) { view.viewTreeObserver.draw() }
    result("fitting_text_does_not_restart", 0, scheduler.pending.size)
    root.width = 400
    view.viewTreeObserver.draw()
    result("layout_recovery_resumes_one_frame", 1, scheduler.pending.size)
    result("layout_recovery_removes_listener", 0, view.viewTreeObserver.listeners.size)
    view.isAttachedToWindow = false
    view.viewTreeObserver.draw()
    scheduler.frame(++time)
    result("active_detach_stops_frames", 0, scheduler.pending.size)
    controller.onPreDraw()
    result("late_draw_after_detach_cannot_revive", 0, scheduler.pending.size)

    view.isAttachedToWindow = true
    root.visibility = View.INVISIBLE
    controller.start(1000f)
    scheduler.frame(++time)
    controller.stop() // The production detach listener invokes stopMarquee/stop.
    root.visibility = View.VISIBLE
    controller.onPreDraw()
    result("stop_while_hidden_removes_listener", 0, view.viewTreeObserver.listeners.size)
    result("stop_while_hidden_cannot_revive", 0, scheduler.pending.size)
    controller.start(1000f)
    root.visibility = View.INVISIBLE
    scheduler.frame(++time)
    MarqueeHook.islandMarqueeState[root] = false
    view.viewTreeObserver.draw()
    result("disable_while_hidden_removes_listener", 0, view.viewTreeObserver.listeners.size)
    result("disable_while_hidden_has_no_frame", 0, scheduler.pending.size)
    MarqueeHook.islandMarqueeState[root] = true
    view.text = "Replacement notification"
    controller.start(1000f)
    scheduler.frame(++time)
    result("replacement_while_hidden_waits_once", 1, view.viewTreeObserver.listeners.size)
    result("replacement_while_hidden_does_not_poll", 0, scheduler.pending.size)
    root.visibility = View.VISIBLE
    view.viewTreeObserver.draw()
    result("replacement_resumes_once", 1, scheduler.pending.size)
    controller.stop()
    result("final_cleanup_no_frames", 0, scheduler.pending.size)
    result("final_cleanup_no_listeners", 0, view.viewTreeObserver.listeners.size)
    pauseCounterCases()
    pauseLifecycleCases()
    pauseDeadlineCases()
}

private class PauseScene {
    val scheduler = Choreographer.getInstance().apply { reset() }
    val root = ViewGroup().apply { width = 400 }
    val view = TextView().apply { parent = root }
    val controller = MarqueeHook.MarqueeController(view)
    init {
        MarqueeHook.islandMarqueeState.clear()
        MarqueeHook.islandAutoHideSessions.clear()
        MarqueeHook.loopCalls = 0; MarqueeHook.loopAction = null
        MarqueeHook.islandMarqueeState[root] = true
        controller.start(1000f)
    }
    fun first() { scheduler.frame(1_000_000_000L) }
    fun end() { first(); scheduler.frame(2_500_000_000L); scheduler.frame(8_500_000_000L) }
    fun stop() { controller.stop(); MarqueeHook.loopAction = null }
}

private fun pauseCounterCases() {
    val start = PauseScene(); start.first()
    val initial = start.scheduler.delivered
    // Delivered display opportunities at approximately 120Hz, all before 1500ms expires.
    repeat(179) { start.scheduler.frame(1_000_000_000L + (it + 1L) * 8_333_333L) }
    result("initial_pause_120hz_has_zero_additional_frame_callbacks", 0, start.scheduler.delivered - initial)
    start.stop()
    val end = PauseScene(); end.end()
    val atEnd = end.scheduler.delivered
    repeat(120) { end.scheduler.frame(8_500_000_000L + (it + 1L) * 8_333_333L) }
    result("end_pause_120hz_has_zero_additional_frame_callbacks", 0, end.scheduler.delivered - atEnd)
    end.stop()
}

private fun pauseLifecycleCases() {
    run {
        val s = PauseScene(); s.first()
        result("initial_pause_has_one_delayed_wake", 1, s.scheduler.pending.count { it.delayed })
        result("initial_pause_has_no_immediate_frame", 0, s.scheduler.pending.count { !it.delayed })
        result("initial_pause_has_one_passive_draw_listener", 1, s.view.viewTreeObserver.listeners.size)
        val posts = s.scheduler.delayedPosts
        repeat(100) { s.controller.start(1000f); s.view.viewTreeObserver.draw() }
        result("unchanged_start_and_natural_draw_do_not_repost_delayed_wake", posts, s.scheduler.delayedPosts)
        result("unchanged_start_and_draw_keep_one_listener", 1, s.view.viewTreeObserver.listeners.size)
        s.scheduler.frame(2_499_000_000L)
        result("start_pause_does_not_end_before_1500ms", 1, s.scheduler.delivered)
        s.scheduler.frame(2_500_000_000L)
        result("start_pause_wakes_once_at_deadline", 2, s.scheduler.delivered)
        result("active_scroll_removes_pause_listener", 0, s.view.viewTreeObserver.listeners.size)
        s.scheduler.frame(3_500_000_000L)
        result("active_speed_after_delayed_wake_is_100px_per_second", 100, s.view.scrollX)
        s.stop()
    }
    run {
        val s = PauseScene(); s.end()
        result("end_pause_has_one_delayed_wake", 1, s.scheduler.pending.count { it.delayed })
        result("end_pause_keeps_original_endpoint", 600, s.view.scrollX)
        result("loop_not_counted_at_endpoint", 0, MarqueeHook.loopCalls)
        s.scheduler.frame(9_500_000_000L)
        result("strict_1000ms_end_pause_not_counted_early", 0, MarqueeHook.loopCalls)
        s.scheduler.frame(9_501_000_000L)
        result("one_loop_counted_after_original_end_pause", 1, MarqueeHook.loopCalls)
        result("next_loop_returns_to_original_start", 0, s.view.scrollX)
        result("next_loop_has_one_start_delay", 1, s.scheduler.pending.count { it.delayed })
        repeat(20) { s.view.viewTreeObserver.draw() }
        result("natural_draw_does_not_count_additional_loops", 1, MarqueeHook.loopCalls)
        s.stop()
    }
    run {
        val s = PauseScene(); s.first(); s.root.visibility = View.GONE; s.view.viewTreeObserver.draw()
        result("hide_during_start_pause_cancels_delayed_wake", 0, s.scheduler.pending.size)
        result("hidden_pause_keeps_only_one_readiness_listener", 1, s.view.viewTreeObserver.listeners.size)
        s.scheduler.frame(10_000_000_000L)
        result("cancelled_hidden_wake_is_not_delivered", 1, s.scheduler.delivered)
        s.root.visibility = View.VISIBLE; s.view.viewTreeObserver.draw()
        result("show_after_paused_hide_posts_one_frame", 1, s.scheduler.pending.size)
        s.scheduler.frame(10_016_000_000L)
        result("show_after_hidden_pause_restarts_original_start_delay", 1, s.scheduler.pending.count { it.delayed })
        result("hidden_interval_does_not_advance_scroll", 0, s.view.scrollX)
        s.stop()
    }
    run {
        val s = PauseScene(); s.end(); s.root.alpha = 0f; s.view.viewTreeObserver.draw()
        result("hide_during_end_pause_cancels_delayed_wake", 0, s.scheduler.pending.size)
        result("hidden_end_pause_never_counts_loop", 0, MarqueeHook.loopCalls)
        result("hidden_end_pause_resets_old_scroll", 0, s.view.scrollX)
        s.stop()
    }
    run {
        val s = PauseScene(); s.end(); s.root.width = 600; s.view.viewTreeObserver.draw()
        result("native_pause_layout_change_keeps_updated_endpoint", 400, s.view.scrollX)
        result("native_geometry_draw_does_not_add_frame", 1, s.scheduler.pending.size)
        s.root.width = 1200; s.view.viewTreeObserver.draw()
        result("fitting_text_during_pause_cancels_wake", 0, s.scheduler.pending.size)
        result("fitting_text_during_pause_resets_offset", 0, s.view.scrollX)
        s.stop()
    }
    run {
        val s = PauseScene(); s.first()
        val tree = s.view.viewTreeObserver
        s.view.viewTreeObserver = ViewTreeObserver()
        s.stop()
        result("observer_replacement_stop_removes_registered_old_tree", 0, tree.listeners.size)
        result("observer_replacement_stop_keeps_new_tree_empty", 0, s.view.viewTreeObserver.listeners.size)
        result("observer_replacement_stop_cancels_delay", 0, s.scheduler.pending.size)
    }
    run {
        val s = PauseScene(); s.first()
        MarqueeHook.islandMarqueeState[s.root] = false; s.view.viewTreeObserver.draw()
        result("disable_during_pause_removes_listener", 0, s.view.viewTreeObserver.listeners.size)
        result("disable_during_pause_cancels_wake", 0, s.scheduler.pending.size)
        s.controller.onPreDraw(); s.scheduler.frame(5_000_000_000L)
        result("late_draw_after_disable_cannot_restart", 0, s.scheduler.pending.size)
        s.stop()
    }
    run {
        val s = PauseScene(); s.first()
        val previous = s.scheduler.pending.single().callback
        s.stop(); previous.doFrame(3_000_000_000L)
        result("late_frame_after_stop_cannot_restart", 0, s.scheduler.pending.size)
        result("late_frame_after_stop_keeps_start_offset", 0, s.view.scrollX)
    }
    run {
        val s = PauseScene(); s.first()
        s.view.text = "new notification"; s.controller.start(1000f)
        result("replacement_cancels_old_delay_and_posts_one_initial_frame", 1, s.scheduler.pending.count { !it.delayed })
        result("replacement_removes_old_pause_listener", 0, s.view.viewTreeObserver.listeners.size)
        s.scheduler.frame(2_000_000_000L)
        s.scheduler.frame(2_500_000_000L)
        result("replacement_does_not_reuse_old_deadline", 2, s.scheduler.delivered)
        s.scheduler.frame(3_500_000_000L)
        result("replacement_wakes_at_its_own_deadline", 3, s.scheduler.delivered)
        s.stop()
    }
    run {
        val s = PauseScene(); s.end()
        MarqueeHook.loopAction = { s.controller.stop() }
        s.scheduler.frame(9_501_000_000L)
        result("auto_hide_stop_during_loop_cannot_schedule_next_pause", 0, s.scheduler.pending.size)
        result("auto_hide_stop_during_loop_removes_draw_listener", 0, s.view.viewTreeObserver.listeners.size)
        s.stop()
    }
    run {
        val s = PauseScene(); s.end()
        MarqueeHook.loopAction = {
            s.view.text = "replacement at loop completion"; s.controller.start(1000f)
        }
        s.scheduler.frame(9_501_000_000L)
        result("loop_callback_replacement_keeps_one_new_initial_frame", 1, s.scheduler.pending.count { !it.delayed })
        result("old_loop_does_not_overwrite_replacement_with_delay", 0, s.scheduler.pending.count { it.delayed })
        s.stop()
    }
    run {
        val s = PauseScene(); s.first()
        result("pause_controller_has_no_strong_view_or_observer_field", 0,
            s.controller.javaClass.declaredFields.count { View::class.java.isAssignableFrom(it.type) || ViewTreeObserver::class.java.isAssignableFrom(it.type) })
        s.stop()
    }
}


private fun pauseDeadlineCases() {
    run {
        val s = PauseScene()
        s.scheduler.frame(1_000_000_000L, 1_200_000_000L)
        result("late_start_dispatch_retains_original_1500ms_deadline", 2500,
            (s.scheduler.pending.single().due / 1_000_000L).toInt())
        s.scheduler.frame(2_499_000_000L)
        result("late_start_dispatch_does_not_wake_early", 1, s.scheduler.delivered)
        s.scheduler.frame(2_500_000_000L)
        result("late_start_dispatch_wakes_at_original_deadline", 2, s.scheduler.delivered)
        s.scheduler.frame(3_500_000_000L)
        result("late_start_dispatch_does_not_change_scroll_speed", 100, s.view.scrollX)
        s.stop()
    }
    run {
        val s = PauseScene(); s.first(); s.scheduler.frame(2_500_000_000L)
        s.scheduler.frame(8_500_000_000L, 8_700_000_000L)
        result("late_endpoint_dispatch_retains_original_1001ms_deadline", 9501,
            (s.scheduler.pending.single().due / 1_000_000L).toInt())
        s.scheduler.frame(9_500_000_000L)
        result("late_endpoint_dispatch_does_not_count_loop_early", 0, MarqueeHook.loopCalls)
        s.scheduler.frame(9_501_000_000L)
        result("late_endpoint_dispatch_counts_loop_at_original_deadline", 1, MarqueeHook.loopCalls)
        result("late_endpoint_dispatch_preserves_next_loop_start", 0, s.view.scrollX)
        s.stop()
    }
    run {
        val s = PauseScene()
        s.scheduler.frame(1_000_000_000L, 3_000_000_000L)
        result("overdue_pause_does_not_add_another_relative_delay", 3000,
            (s.scheduler.pending.single().due / 1_000_000L).toInt())
        s.scheduler.frame(3_016_000_000L)
        result("overdue_pause_starts_scrolling_on_next_display_opportunity", 1,
            s.scheduler.pending.count { !it.delayed })
        s.stop()
    }
}
