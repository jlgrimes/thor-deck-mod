package dev.thor.deck;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Client-only companion for the AYN Thor bottom-screen deck. Files under
 * {@code <gameDir>/thor_deck/} are the IPC bus (no sockets):
 * <ul>
 *   <li>{@code inventory.json} — mod writes, launcher reads</li>
 *   <li>{@code icons/<stem>.png} — mod writes, launcher reads</li>
 *   <li>{@code command.json} — launcher writes, mod reads (tap-to-move)</li>
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
        System.out.println("[ThorDeck] inventory -> " + outFile + "  icons -> " + iconDir);

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        pollCommand(client, player);
        if (++tickCounter < WRITE_EVERY_TICKS) {
            return;
        }
        tickCounter = 0;
        writeInventory(client, player);
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
            Integer from = readIntField(json, "from");
            Integer to = readIntField(json, "to");
            if (seq == null || from == null || to == null) {
                return;
            }
            if (seq <= lastHandledSeq) {
                return;
            }
            lastHandledSeq = seq;
            DeckSlots.swap(client, player, from, to);
        } catch (Exception ignored) {
            // missing / partial / racing with the launcher write
        }
    }

    private void writeInventory(Minecraft client, LocalPlayer player) {
        Inventory inv = player.getInventory();
        String json = buildJson(client, inv, player);
        if (json.equals(lastJson)) {
            return;
        }
        lastJson = json;
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
                .append(",\"selected\":").append(selected)
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
            sb.append("{\"i\":").append(i)
                    .append(",\"id\":"\"").append(escape(id)).append('"')
                    .append(",\"n\":"\"").append(name).append('"')
                    .append(",\"c\":").append(stack.getCount())
                    .append(",\"icon\":"\"").append(escape(stem)).append('"')
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static Integer readIntField(String json, String key) {
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
        while (k < json.length() && Character.isDigit(json.charAt(k))) {
            k++;
        }
        if (k == j || (json.charAt(j) == '-' && k == j + 1)) {
            return null;
        }
        try {
            return Integer.parseInt(json.substring(j, k));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
