package dev.thor.deck;

import java.nio.file.Path;

/**
 * Combined file bus for ControlDeckPresentation ({@code feat/dualscreen-deck-v2}).
 *
 * <p>Primary: {@code thor_deck/state.json} with a monotonic {@code seq}. Nested
 * {@code map}, {@code inventory} (slots), {@code chat} (lines), {@code hud}
 * (hp/hunger/coords/yaw), and {@code lastWalk} after a map tap is consumed. Split files stay as fallback:
 * inventory.json / map.json / hud.json / chat.json. {@code map.png}, {@code icons/},
 * and {@code command.json} are written/read beside this object.
 */
public final class DeckBus {
    private static Path dir;
    private static String inventory = "{\"size\":41,\"selected\":0,\"slots\":[]}";
    private static String chat = "{\"seq\":0,\"lines\":[]}";
    private static String hud = "{}";
    private static String map = "{}";
    private static String lastWalk = "";
    private static int seq = 0;
    private static boolean dirty;

    private DeckBus() {}

    public static synchronized void init(Path outDir) {
        dir = outDir;
    }

    public static synchronized int seq() {
        return seq;
    }

    public static synchronized void setInventory(String json) {
        if (json == null || json.equals(inventory)) {
            return;
        }
        inventory = json;
        dirty = true;
    }

    public static synchronized void setChat(String json) {
        if (json == null || json.equals(chat)) {
            return;
        }
        chat = json;
        dirty = true;
    }

    public static synchronized void setHud(String json) {
        if (json == null || json.equals(hud)) {
            return;
        }
        hud = json;
        dirty = true;
    }

    public static synchronized void setMap(String json) {
        if (json == null || json.equals(map)) {
            return;
        }
        map = json;
        dirty = true;
    }

    /** Last consumed map-walk command. Nested in {@code state.json} as {@code lastWalk}. */
    public static synchronized void setLastWalk(String json) {
        if (json == null || json.equals(lastWalk)) {
            return;
        }
        lastWalk = json;
        dirty = true;
    }

    /** Assemble and atomically write {@code state.json}. Safe from the map worker. */
    public static synchronized void flush() {
        if (!dirty || dir == null) {
            return;
        }
        dirty = false;
        seq++;
        String state = "{\"seq\":" + seq
                + ",\"map\":" + objectOrEmpty(map)
                + ",\"inventory\":" + objectOrEmpty(inventory)
                + ",\"chat\":" + objectOrEmpty(chat)
                + ",\"hud\":" + objectOrEmpty(hud)
                + (lastWalk.isEmpty() ? "" : ",\"lastWalk\":" + lastWalk)
                + "}";
        DeckMap.atomicWrite(dir.resolve("state.json"), dir.resolve("state.json.tmp"), state);
    }

    private static String objectOrEmpty(String json) {
        if (json == null || json.isEmpty()) {
            return "{}";
        }
        return json;
    }
}
