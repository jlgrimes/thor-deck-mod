package dev.thor.deck;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Last 40 chat/system/action lines → {@code thor_deck/chat.json}.
 * harden-0502: fabric-api ClientReceiveMessageEvents is optional/reflective —
 * no hard import so Knot/Android loads DeckChat when fabric-api is absent.
 * Writes are debounced by 2 ticks.
 */
public final class DeckChat {
    private static final int CAP = 40;
    private static final int DEBOUNCE_TICKS = 2;

    private static final Deque<Line> LINES = new ArrayDeque<>(CAP + 1);
    private static Path dir;
    private static int seq = 0;
    private static int debounce = -1;
    private static boolean dirty = false;
    private static boolean hooksOk = false;

    private DeckChat() {}

    public static void init(Path outDir) {
        dir = outDir;
        hooksOk = false;
        try {
            Class<?> events = Class.forName(
                    "net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents");
            Object chat = events.getField("CHAT").get(null);
            Object game = events.getField("GAME").get(null);
            Class<?> chatIface = Class.forName(
                    "net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents$Chat");
            Class<?> gameIface = Class.forName(
                    "net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents$Game");
            Method chatReg = chat.getClass().getMethod("register", chatIface);
            Method gameReg = game.getClass().getMethod("register", gameIface);
            Object chatHook = Proxy.newProxyInstance(
                    chatIface.getClassLoader(),
                    new Class<?>[]{chatIface},
                    (proxy, method, args) -> {
                        if (args != null && args.length >= 1) {
                            try {
                                Object message = args[0];
                                Object sender = args.length > 2 ? args[2] : null;
                                append(profileName(sender), textOf(message), "chat");
                            } catch (Exception ignored) {
                            }
                        }
                        return null;
                    });
            Object gameHook = Proxy.newProxyInstance(
                    gameIface.getClassLoader(),
                    new Class<?>[]{gameIface},
                    (proxy, method, args) -> {
                        if (args != null && args.length >= 1) {
                            try {
                                Object message = args[0];
                                boolean overlay = args.length > 1 && Boolean.TRUE.equals(args[1]);
                                append("", textOf(message), overlay ? "action" : "system");
                            } catch (Exception ignored) {
                            }
                        }
                        return null;
                    });
            chatReg.invoke(chat, chatHook);
            gameReg.invoke(game, gameHook);
            hooksOk = true;
            System.out.println("[ThorDeck] DeckChat hooks via reflective fabric-api");
        } catch (Throwable t) {
            System.err.println("[ThorDeck] DeckChat hooks skipped (no fabric-api): " + t);
        }
    }

    public static boolean hooksActive() {
        return hooksOk;
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

    /** Host/test helper: append a line without fabric-api. */
    public static void appendForTest(String from, String text, String kind) {
        append(from == null ? "" : from, text, kind == null ? "chat" : kind);
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
        sb.append("{\"seq\":").append(seq).append(",\"lines\":[");
        boolean first = true;
        for (Line line : LINES) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"from\":\"").append(escape(line.from)).append('"')
                    .append(",\"text\":\"").append(escape(line.text)).append('"')
                    .append(",\"kind\":\"").append(line.kind).append("\"}");
        }
        sb.append("]}");
        String json = sb.toString();
        atomicWrite(dir.resolve("chat.json"), dir.resolve("chat.json.tmp"), json);
        try {
            DeckBus.setChat(json);
        } catch (Throwable ignored) {
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        try {
            return DeckMap.escape(s);
        } catch (Throwable t) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    private static void atomicWrite(Path dest, Path tmp, String json) {
        try {
            java.nio.file.Files.writeString(tmp, json);
            try {
                java.nio.file.Files.move(tmp, dest,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                java.nio.file.Files.move(tmp, dest,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
        }
    }

    private static String textOf(Object message) {
        if (message == null) {
            return "";
        }
        try {
            if (message instanceof Component) {
                return ((Component) message).getString();
            }
            Method m = message.getClass().getMethod("getString");
            Object r = m.invoke(message);
            return r == null ? "" : r.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String profileName(Object sender) {
        if (sender == null) {
            return "";
        }
        try {
            if (sender instanceof GameProfile) {
                String n = ((GameProfile) sender).name();
                return n == null ? "" : n;
            }
            Method m = sender.getClass().getMethod("name");
            Object r = m.invoke(sender);
            return r == null ? "" : r.toString();
        } catch (Exception e) {
            try {
                Method m = sender.getClass().getMethod("getName");
                Object r = m.invoke(sender);
                return r == null ? "" : r.toString();
            } catch (Exception e2) {
                return "";
            }
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
