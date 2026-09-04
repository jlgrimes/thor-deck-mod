package dev.thor.deck;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * harden-0421: fabric-api-free tick loop (successor to harden-1839).
 * Schedules onClientTick on the Minecraft render thread via Executor.execute.
 * After each tick: DeckBus.flush() + thor_deck/tick.heartbeat so live writes
 * are observable even when DeckHud/DeckMap mappings throw.
 */
public final class ZeroApiTickBoot {
    private static final AtomicInteger ERR_LOGGED = new AtomicInteger(0);
    private static final AtomicInteger OK_LOGGED = new AtomicInteger(0);
    private static final AtomicLong TICKS = new AtomicLong(0);
    private static final AtomicBoolean PLAYER_SEEN = new AtomicBoolean(false);

    private ZeroApiTickBoot() {}

    public static void start(Object mod) {
        AtomicBoolean pending = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            Method getInstance = null;
            Method onTick = null;
            Method execute = null;
            Method flush = null;
            Field playerField = null;
            Class<?> mcClass = null;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (mcClass == null) {
                        mcClass = Class.forName("net.minecraft.class_310");
                        getInstance = mcClass.getMethod("method_1551");
                        onTick = mod.getClass().getDeclaredMethod("onClientTick", mcClass);
                        onTick.setAccessible(true);
                        execute = mcClass.getMethod("execute", Runnable.class);
                        try {
                            flush = Class.forName("dev.thor.deck.DeckBus").getMethod("flush");
                        } catch (Throwable ignored) {
                            flush = null;
                        }
                        try {
                            // LocalPlayer field on MinecraftClient (intermediary): field_1724
                            playerField = mcClass.getField("field_1724");
                        } catch (Throwable ignored) {
                            playerField = null;
                        }
                    }
                    Object client = getInstance.invoke(null);
                    if (client != null && pending.compareAndSet(false, true)) {
                        Method onTickF = onTick;
                        Method flushF = flush;
                        Field playerF = playerField;
                        Object modF = mod;
                        try {
                            execute.invoke(client, (Runnable) () -> {
                                try {
                                    onTickF.invoke(modF, client);
                                    if (flushF != null) {
                                        try { flushF.invoke(null); } catch (Throwable ignored) {}
                                    }
                                    long n = TICKS.incrementAndGet();
                                    Object player = null;
                                    if (playerF != null) {
                                        try { player = playerF.get(client); } catch (Throwable ignored) {}
                                    }
                                    if (player != null && PLAYER_SEEN.compareAndSet(false, true)) {
                                        System.out.println("[ThorDeck] zero-api first player tick n=" + n);
                                    }
                                    if (player != null && OK_LOGGED.get() < 4) {
                                        OK_LOGGED.incrementAndGet();
                                        System.out.println("[ThorDeck] zero-api tick ok n=" + n + " player=yes");
                                    }
                                    writeHeartbeat(modF, n, player != null);
                                } catch (Throwable e) {
                                    logErr("onClientTick", e);
                                } finally {
                                    pending.set(false);
                                }
                            });
                        } catch (Throwable e) {
                            pending.set(false);
                            logErr("execute", e);
                        }
                    }
                } catch (Throwable e) {
                    logErr("boot-loop", e);
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "thor-deck-tick");
        t.setDaemon(true);
        t.start();
        System.out.println("[ThorDeck] zero-api tick thread started (harden-0421)");
    }

    private static void writeHeartbeat(Object mod, long n, boolean hasPlayer) {
        try {
            Field dirF = mod.getClass().getDeclaredField("dir");
            dirF.setAccessible(true);
            Object dir = dirF.get(mod);
            if (!(dir instanceof Path)) return;
            Path hb = ((Path) dir).resolve("tick.heartbeat");
            String body = "n=" + n + " player=" + (hasPlayer ? "1" : "0") + " t=" + System.currentTimeMillis() + "\n";
            Files.writeString(hb, body, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }

    private static void logErr(String where, Throwable e) {
        if (ERR_LOGGED.incrementAndGet() > 12) {
            return;
        }
        System.err.println("[ThorDeck] zero-api " + where + ": " + e);
        Throwable c = e.getCause();
        if (c != null) {
            System.err.println("[ThorDeck]   cause: " + c);
            c.printStackTrace(System.err);
        } else {
            e.printStackTrace(System.err);
        }
    }
}
