package com.aibuilder.mod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Runs a single Runnable once, N server ticks from now. Used by /aievent to
 * automatically revert a timed event (restore blocks, despawn mobs) without
 * needing any in-game redstone timer/clock - this is counted purely in our
 * own Java code, which is far more reliable than asking the AI to build a
 * correct scoreboard+repeating-command-block timer every time.
 *
 * Registers exactly ONE tick listener for the whole mod's lifetime, same
 * pattern as {@link BuildScheduler}.
 */
public final class EventScheduler {

    // ticksRemaining is mutable per-entry, so this stays a plain object list
    // with a parallel countdown field rather than records.
    private static final class Entry {
        int ticksRemaining;
        final Runnable task;

        Entry(int ticksRemaining, Runnable task) {
            this.ticksRemaining = ticksRemaining;
            this.task = task;
        }
    }

    // Runs task every intervalTicks, until totalTicksRemaining runs out -
    // used for a continuous effect for the rest of a timed event (e.g. an
    // item rain), rather than one thing happening once at the very end.
    private static final class RepeatingEntry {
        final int intervalTicks;
        int sinceLast;
        int totalTicksRemaining;
        final Runnable task;

        RepeatingEntry(int intervalTicks, int totalTicksRemaining, Runnable task) {
            this.intervalTicks = Math.max(1, intervalTicks);
            this.sinceLast = 0;
            this.totalTicksRemaining = totalTicksRemaining;
            this.task = task;
        }
    }

    private static final List<Entry> entries = new ArrayList<>();
    private static final List<RepeatingEntry> repeatingEntries = new ArrayList<>();
    private static boolean registered = false;

    private EventScheduler() {
    }

    public static void init() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    /** Runs task once, delaySeconds from now (20 ticks per second). */
    public static void scheduleSeconds(int delaySeconds, Runnable task) {
        entries.add(new Entry(Math.max(1, delaySeconds * 20), task));
    }

    /**
     * Runs task every intervalTicks, for about totalSeconds (20 ticks per
     * second) and then stops automatically. Used for an effect that should
     * keep happening throughout a timed /aievent (e.g. a continuous item
     * rain), independent of - and on top of - that event's one-time revert
     * scheduled separately via {@link #scheduleSeconds}.
     */
    public static void scheduleRepeating(int intervalTicks, int totalSeconds, Runnable task) {
        repeatingEntries.add(new RepeatingEntry(intervalTicks, Math.max(1, totalSeconds * 20), task));
    }

    private static void tick() {
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            entry.ticksRemaining--;
            if (entry.ticksRemaining <= 0) {
                it.remove();
                entry.task.run();
            }
        }

        Iterator<RepeatingEntry> rit = repeatingEntries.iterator();
        while (rit.hasNext()) {
            RepeatingEntry entry = rit.next();
            entry.totalTicksRemaining--;
            if (entry.totalTicksRemaining <= 0) {
                rit.remove();
                continue;
            }
            entry.sinceLast++;
            if (entry.sinceLast >= entry.intervalTicks) {
                entry.sinceLast = 0;
                entry.task.run();
            }
        }
    }
}
