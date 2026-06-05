package dev.thor.deck;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Spike: every few ticks, write the local player's inventory to a JSON file in the game directory.
 * The Amethyst dual-screen app reads that file and renders the inventory on the AYN Thor bottom
 * screen. File-based IPC keeps the mod fully decoupled from the launcher (no sockets, same device).
 */
public class ThorDeckMod implements ClientModInitializer {
    private static final int WRITE_EVERY_TICKS = 4; // ~5 writes/sec

    private int tickCounter = 0;
    private Path outFile;
    private Path tmpFile;
    private String lastJson = "";

    @Override
    public void onInitializeClient() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("thor_deck");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            System.err.println("[ThorDeck] could not create output dir: " + e);
        }
        outFile = dir.resolve("inventory.json");
        tmpFile = dir.resolve("inventory.json.tmp");
        System.out.println("[ThorDeck] writing inventory to " + outFile);

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
        if (++tickCounter < WRITE_EVERY_TICKS) return;
        tickCounter = 0;

        LocalPlayer player = client.player;
        if (player == null) return;

        String json = buildJson(player.getInventory());
        if (json.equals(lastJson)) return; // only write on change
        lastJson = json;

        try {
            Files.writeString(tmpFile, json);
            Files.move(tmpFile, outFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            // Non-atomic fallback (some filesystems don't support ATOMIC_MOVE)
            try {
                Files.writeString(outFile, json);
            } catch (Exception ignored) {
            }
        }
    }

    private String buildJson(Inventory inv) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"size\":").append(inv.getContainerSize()).append(",\"slots\":[");
        int n = inv.getContainerSize();
        boolean first = true;
        for (int i = 0; i < n; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (!first) sb.append(',');
            first = false;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String name = escape(stack.getHoverName().getString());
            sb.append("{\"i\":").append(i)
                    .append(",\"id\":\"").append(escape(id)).append('"')
                    .append(",\"n\":\"").append(name).append('"')
                    .append(",\"c\":").append(stack.getCount())
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
