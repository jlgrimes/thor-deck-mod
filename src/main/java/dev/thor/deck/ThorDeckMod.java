package dev.thor.deck;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Client-only companion for the AYN Thor bottom-screen deck. Files under
 * {@code <gameDir>/thor_deck/} are the IPC bus (no sockets):
 * <ul>
 *   <li>{@code state.json} — combined bus, monotonic seq (map + inventory + chat + hud + lastWalk)</li>
 *   <li>{@code inventory.json} — split-file fallback</li>
 *   <li>{@code icons/<stem>.png} — mod writes, launcher reads</li>
 *   <li>{@code command.json} — launcher writes, mod reads (tap-to-move and type=walk)</li>
 *   <li>{@code map.png} / {@code map.json} — CPU minimap (MapColor sample)</li>
 *   <li>{@code hud.json} — health, hunger, pos, biome, time, effects</li>
 *   <li>{@code chat.json} — last 40 chat/system/action lines</li>
 * </ul>
 */
public class ThorDeckMod implements ClientModInitializer {
    private static final int WRITE_EVERY_TICKS = 4; // ~5 inventory writes/sec

    private int tickCounter = 0;
    private Path dir;
    private Path outFile;
    private Path tmpFile;
    private Path iconDir;
    private Path commandFile;
    private String lastJson = "";
    private long lastHandledSeq = 0;
    private long lastCommandMtime = -1;

    @Override
    public void onInitializeClient() {
        dir = FabricLoader.getInstance().getGameDir().resolve("thor_deck");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            System.err.println("[ThorDeck] could not create output dir: " + e);
        }
        outFile = dir.resolve("inventory.json");
        tmpFile = dir.resolve("inventory.json.tmp");
        iconDir = dir.resolve("icons");
        commandFile = dir.resolve("command.json");
        try {
            Files.createDirectories(iconDir);
        } catch (Exception e) {
            System.err.println("[ThorDeck] could not create icons dir: " + e);
        }
        System.out.println("[ThorDeck] inventory -> " + outFile + "  icons -> " + iconDir
                + "  map/hud/chat -> " + dir);
        DeckChat.init(dir);
        DeckBus.init(dir);

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
        DeckChat.tick();
        LocalPlayer player = client.player;
        if (player == null) {
            DeckBus.flush();
            return;
        }
        pollCommand(client, player);
        DeckHud.tick(client, player, dir);
        DeckMap.tick(client, player, dir);
        if (++tickCounter >= WRITE_EVERY_TICKS) {
            tickCounter = 0;
            writeInventory(client, player);
        }
        DeckBus.flush();
    }

    private void pollCommand(Minecraft client, LocalPlayer player) {
        try {
            if (!Files.exists(commandFile)) {
                return;
            }
            long mtime = Files.getLastModifiedTime(commandFile).toMillis();
            if (mtime == lastCommandMtime) {
                return;
            }
            lastCommandMtime = mtime;
            String json = Files.readString(commandFile, StandardCharsets.UTF_8);
            Integer seq = readIntField(json, "seq");
            if (seq == null) {
                return;
            }
            if (seq <= lastHandledSeq) {
                return;
            }
            String type = readStringField(json, "type");
            if (type != null && "walk".equalsIgnoreCase(type.trim())) {
                lastHandledSeq = seq;
                applyWalk(client, player, seq, json);
                return;
            }
            Integer from = readIntField(json, "from");
            Integer to = readIntField(json, "to");
            if (from == null || to == null) {
                return;
            }
            lastHandledSeq = seq;
            DeckSlots.swap(client, player, from, to);
        } catch (Exception ignored) {
            // missing / partial / racing with the launcher write
        }
    }

    /**
     * Consume a MAP-tab walk. Dest prefers world {@code x}/{@code z}; {@code mx}/{@code mz}
     * are 128×128 minimap cells (player cell is (64, 64)). Moves via
     * {@code Entity.setPos} (official Mojang 1.21.11) and records {@code lastWalk}
     * on the combined bus so a consume is never a silent drop.
     */
    private void applyWalk(Minecraft client, LocalPlayer player, int seq, String json) {
        Double x = readNumberField(json, "x");
        Double z = readNumberField(json, "z");
        Integer mx = readIntField(json, "mx");
        Integer mz = readIntField(json, "mz");
        Double scaleN = readNumberField(json, "scale");
        double scale = (scaleN != null && scaleN > 0) ? scaleN : 1.0;

        Double destX = null;
        Double destZ = null;
        if (x != null && z != null) {
            destX = x;
            destZ = z;
        } else if (mx != null && mz != null) {
            destX = player.getX() + (mx - DeckMap.HALF) * scale;
            destZ = player.getZ() + (mz - DeckMap.HALF) * scale;
        }

        if (destX == null || destZ == null) {
            System.out.println("[ThorDeck] walk seq=" + seq + " no dest (need x/z or mx/mz)");
            DeckBus.setLastWalk(walkJson(seq, mx, mz, null, null, player.getY(), false));
            return;
        }

        double destY = player.getY();
        try {
            if (client.level != null) {
                int wx = (int) Math.floor(destX);
                int wz = (int) Math.floor(destZ);
                int surface = client.level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
                if (surface > client.level.getMinY()) {
                    destY = surface;
                }
            }
        } catch (Exception ignored) {
        }

        boolean applied = false;
        try {
            player.setPos(destX, destY, destZ);
            player.setOldPosAndRot();
            applied = true;
        } catch (Exception e) {
            System.err.println("[ThorDeck] walk setPos failed: " + e);
        }
        System.out.println("[ThorDeck] walk seq=" + seq
                + " dest=" + destX + "," + destZ + " y=" + destY
                + " applied=" + applied);
        DeckBus.setLastWalk(walkJson(seq, mx, mz, destX, destZ, destY, applied));
    }

    private static String walkJson(int seq, Integer mx, Integer mz,
                                   Double x, Double z, double y, boolean applied) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"seq\":").append(seq).append(",\"type\":\"walk\"");
        if (mx != null) {
            sb.append(",\"mx\":").append(mx);
        }
        if (mz != null) {
            sb.append(",\"mz\":").append(mz);
        }
        if (x != null) {
            sb.append(",\"x\":").append(DeckMap.num(x));
        }
        if (z != null) {
            sb.append(",\"z\":").append(DeckMap.num(z));
        }
        sb.append(",\"y\":").append(DeckMap.num(y));
        sb.append(",\"applied\":").append(applied);
        sb.append('}');
        return sb.toString();
    }

    private void writeInventory(Minecraft client, LocalPlayer player) {
        Inventory inv = player.getInventory();
        String json = buildJson(client, inv, player);
        if (json.equals(lastJson)) {
            return;
        }
        lastJson = json;
        DeckBus.setInventory(json);
        try {
            Files.writeString(tmpFile, json, StandardCharsets.UTF_8);
            Files.move(tmpFile, outFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.writeString(outFile, json, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
    }

    private String buildJson(Minecraft client, Inventory inv, LocalPlayer player) {
        StringBuilder sb = new StringBuilder(2048);
        int selected = 0;
        try {
            selected = inv.getSelectedSlot();
        } catch (Exception ignored) {
        }
        if (selected < 0 || selected > 8) {
            selected = 0;
        }
        sb.append("{\"size\":" ).append(DeckSlots.SIZE)
                .append(",\"selected\":" ).append(selected)
                .append(",\"slots\":[");
        boolean first = true;
        for (int i = 0; i < DeckSlots.SIZE; i++) {
            ItemStack stack = DeckSlots.get(inv, player, i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String name = escape(stack.getHoverName().getString());
            String stem = ItemIcons.stemFor(stack);
            ItemIcons.export(client, stack, iconDir, stem);
            sb.append("{\"i\":" ).append(i)
                    .append(",\"id\":\"").append(escape(id)).append('"')
                    .append(",\"n\":\"").append(name).append('"')
                    .append(",\"c\":" ).append(stack.getCount())
                    .append(",\"icon\":\"").append(escape(stem)).append('"')
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static Integer readIntField(String json, String key) {
        Double n = readNumberField(json, key);
        if (n == null) {
            return null;
        }
        return n.intValue();
    }

    private static Double readNumberField(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return null;
        }
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
            j++;
        }
        int k = j;
        if (k < json.length() && json.charAt(k) == '-') {
            k++;
        }
        boolean digit = false;
        while (k < json.length() && Character.isDigit(json.charAt(k))) {
            digit = true;
            k++;
        }
        if (k < json.length() && json.charAt(k) == '.') {
            k++;
            while (k < json.length() && Character.isDigit(json.charAt(k))) {
                digit = true;
                k++;
            }
        }
        if (!digit) {
            return null;
        }
        try {
            return Double.parseDouble(json.substring(j, k));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readStringField(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return null;
        }
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
            j++;
        }
        if (j >= json.length() || json.charAt(j) != '"') {
            return null;
        }
        j++;
        StringBuilder sb = new StringBuilder();
        while (j < json.length()) {
            char c = json.charAt(j);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\' && j + 1 < json.length()) {
                sb.append(json.charAt(j + 1));
                j += 2;
                continue;
            }
            sb.append(c);
            j++;
        }
        return null;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
