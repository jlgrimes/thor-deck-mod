package dev.thor.deck;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Last 40 chat/system/action lines → {@code thor_deck/chat.json}.
 * Subscribes to Fabric {@link ClientReceiveMessageEvents#CHAT} and
 * {@link ClientReceiveMessageEvents#GAME}. Writes are debounced by 2 ticks.
 */
public final class DeckChat {
    private static final int CAP = 40;
    private static final int DEBOUNCE_TICKS = 2;

    private static final Deque<Line> LINES = new ArrayDeque<>(CAP + 1);
    private static Path dir;
    private static int seq = 0;
    private static int debounce = -1;
    private static boolean dirty = false;

    private DeckChat() {}

    public static void init(Path outDir) {
        dir = outDir;
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) -> {
            try {
                append(profileName(sender), textOf(message), "chat");
            } catch (Exception ignored) {
            }
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            try {
                append("", textOf(message), overlay ? "action" : "system");
            } catch (Exception ignored) {
            }
        });
    }

    public static void tick() {
        try {
            if (!dirty || dir == null) {
                return;
            }
            if (debounce > 0) {
                debounce--;
                if (debounce > 0) {
                    return;
                }
            }
            dirty = false;
            debounce = -1;
            write();
        } catch (Exception ignored) {
        }
    }

    private static void append(String from, String text, String kind) {
        if (text == null) {
            text = "";
        }
        text = stripCodes(text);
        if (text.isEmpty()) {
            return;
        }
        if (from == null) {
            from = "";
        }
        LINES.addLast(new Line(from, text, kind));
        while (LINES.size() > CAP) {
            LINES.removeFirst();
        }
        dirty = true;
        debounce = DEBOUNCE_TICKS;
    }

    private static void write() {
        seq++;
        StringBuilder sb = new StringBuilder(256 + LINES.size() * 64);
        sb.append("{\"seq\":" ).append(seq).append(",\"lines\":[");
        boolean first = true;
        for (Line line : LINES) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"from\":\"").append(DeckMap.escape(line.from)).append('"')
                    .append(",\"text\":\"").append(DeckMap.escape(line.text)).append('"')
                    .append(",\"kind\":\"").append(line.kind).append("\"}");
        }
        sb.append("]}");
        String json = sb.toString();
        DeckMap.atomicWrite(dir.resolve("chat.json"), dir.resolve("chat.json.tmp"), json);
        DeckBus.setChat(json);
    }

    private static String textOf(Component message) {
        if (message == null) {
            return "";
        }
        try {
            return message.getString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String profileName(GameProfile sender) {
        if (sender == null) {
            return "";
        }
        try {
            String n = sender.name();
            return n == null ? "" : n;
        } catch (Exception e) {
            return "";
        }
    }

    /** Strip vanilla {@code §} formatting codes if {@link Component#getString()} left any. */
    static String stripCodes(String s) {
        if (s.indexOf('\u00a7') < 0 && s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '\u00a7' || c == '&') && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if ((n >= '0' && n <= '9') || (n >= 'a' && n <= 'f') || (n >= 'A' && n <= 'F')
                        || n == 'k' || n == 'l' || n == 'm' || n == 'n' || n == 'o' || n == 'r'
                        || n == 'K' || n == 'L' || n == 'M' || n == 'N' || n == 'O' || n == 'R'
                        || n == 'x' || n == 'X') {
                    i++;
                    continue;
                }
            }
            if (c == '\n' || c == '\r') {
                out.append(' ');
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static final class Line {
        final String from;
        final String text;
        final String kind;

        Line(String from, String text, String kind) {
            this.from = from;
            this.text = text;
            this.kind = kind;
        }
    }
}
