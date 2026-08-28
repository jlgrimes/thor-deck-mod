package dev.thor.deck;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * CPU minimap for the AYN Thor bottom screen. Samples block {@link MapColor}s
 * on the client thread (world access is not thread-safe) and writes
 * {@code thor_deck/map.png} + {@code thor_deck/map.json}.
 *
 * <p>Do not FBO-blit the in-game map item — that path is broken on Pojav GLES.
 */
public final class DeckMap {
    public static final int SIZE = 128;
    public static final int HALF = SIZE / 2; // player cell is (64, 64)
    private static final int MIN_INTERVAL_TICKS = 5;
    private static final int PERIOD_TICKS = 10;
    private static final int MAX_SCAN = 24;
    private static final int ABOVE_PLAYER = 16;
    private static final int ABOVE_PLAYER_CAP = 32;
    private static final double MOVE_BLOCKS = 2.0;
    private static final float YAW_DEGREES = 15.0f;

    private static int ticksSinceWrite = PERIOD_TICKS;
    private static int seq = 0;
    private static double lastX = Double.NaN;
    private static double lastZ = Double.NaN;
    private static float lastYaw = Float.NaN;
    /** Last surface Y per pixel, so the next scan can start nearby. */
    private static final int[] lastHeight = new int[SIZE * SIZE];
    private static boolean heightsInit = false;

    private DeckMap() {}

    public static void tick(Minecraft client, LocalPlayer player, Path dir) {
        try {
            if (client.isPaused()) {
                return;
            }
            ClientLevel level = client.level;
            if (level == null || player == null) {
                return;
            }
            ticksSinceWrite++;
            if (ticksSinceWrite < MIN_INTERVAL_TICKS) {
                return;
            }
            boolean due = ticksSinceWrite >= PERIOD_TICKS;
            double x = player.getX();
            double z = player.getZ();
            float yaw = player.getYRot();
            if (!due && !Double.isNaN(lastX)) {
                double dx = x - lastX;
                double dz = z - lastZ;
                if (dx * dx + dz * dz >= MOVE_BLOCKS * MOVE_BLOCKS) {
                    due = true;
                } else if (yawDelta(yaw, lastYaw) >= YAW_DEGREES) {
                    due = true;
                }
            }
            if (!due) {
                return;
            }
            ticksSinceWrite = 0;
            lastX = x;
            lastZ = z;
            lastYaw = yaw;
            sampleAndWrite(level, player, dir);
        } catch (Exception ignored) {
            // a map miss must never crash the game
        }
    }

    private static float yawDelta(float a, float b) {
        if (Float.isNaN(b)) {
            return 360.0f;
        }
        float d = a - b;
        while (d > 180.0f) {
            d -= 360.0f;
        }
        while (d < -180.0f) {
            d += 360.0f;
        }
        return d < 0.0f ? -d : d;
    }

    private static void sampleAndWrite(ClientLevel level, LocalPlayer player, Path dir) {
        NativeImage image = null;
        try {
            if (!heightsInit) {
                Arrays.fill(lastHeight, Integer.MIN_VALUE);
                heightsInit = true;
            }
            image = new NativeImage(SIZE, SIZE, false);
            BlockPos feet = player.blockPosition();
            int originX = feet.getX();
            int originZ = feet.getZ();
            int playerY = feet.getY();
            int maxY = level.getMaxY();
            int minY = level.getMinY();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int[] rowHeight = new int[SIZE];
            Arrays.fill(rowHeight, playerY);

            // py=0 is north (world Z smaller); north neighbor is the previous row.
            for (int py = 0; py < SIZE; py++) {
                int[] nextRow = new int[SIZE];
                for (int px = 0; px < SIZE; px++) {
                    int wx = originX + (px - HALF);
                    int wz = originZ + (py - HALF);
                    int argb = 0xFF000000;
                    int surfaceY = playerY;
                    try {
                        if (level.hasChunkAt(wx, wz)) {
                            Sample s = sampleColumn(level, pos, wx, wz, playerY, minY, maxY,
                                    lastHeight[py * SIZE + px]);
                            surfaceY = s.y;
                            int northY = rowHeight[px];
                            argb = shade(s.color, s.water, s.waterDepth, surfaceY, northY, px, py);
                        }
                    } catch (Exception ignored) {
                    }
                    lastHeight[py * SIZE + px] = surfaceY;
                    nextRow[px] = surfaceY;
                    image.setPixel(px, py, argb);
                }
                rowHeight = nextRow;
            }

            Path png = dir.resolve("map.png");
            Path pngTmp = dir.resolve("map.png.tmp");
            image.writeToFile(pngTmp);
            try {
                Files.move(pngTmp, png, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(pngTmp, png, StandardCopyOption.REPLACE_EXISTING);
            }

            seq++;
            String biome = biomeId(level, player.blockPosition());
            String dim = dimId(level);
            String json = "{\"seq\":" + seq
                    + ",\"x\":" + num(player.getX())
                    + ",\"y\":" + num(player.getY())
                    + ",\"z\":" + num(player.getZ())
                    + ",\"yaw\":" + num(player.getYRot())
                    + ",\"dim\":\"" + escape(dim) + '"'
                    + ",\"w\":" + SIZE
                    + ",\"h\":" + SIZE
                    + ",\"scale\":1"
                    + ",\"biome\":\"" + escape(biome) + "\"}";
            atomicWrite(dir.resolve("map.json"), dir.resolve("map.json.tmp"), json);
        } catch (Exception ignored) {
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private static Sample sampleColumn(ClientLevel level, BlockPos.MutableBlockPos pos,
                                       int wx, int wz, int playerY, int minY, int maxY,
                                       int lastY) {
        int startY = Math.min(playerY + ABOVE_PLAYER, maxY);
        if (lastY > Integer.MIN_VALUE / 2) {
            startY = Math.max(startY, Math.min(lastY + 4, maxY));
        }
        startY = Math.min(startY, Math.min(playerY + ABOVE_PLAYER_CAP, maxY));
        startY = Math.max(startY, minY);
        int endY = Math.max(minY, startY - MAX_SCAN);

        MapColor found = MapColor.NONE;
        int foundY = startY;
        boolean hitWater = false;
        int waterY = startY;
        int waterDepth = 0;

        for (int y = startY; y >= endY; y--) {
            pos.set(wx, y, wz);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                if (hitWater) {
                    break;
                }
                continue;
            }
            MapColor color = state.getMapColor(level, pos);
            boolean water = isWater(state, color);
            if (water) {
                if (!hitWater) {
                    hitWater = true;
                    waterY = y;
                    found = MapColor.WATER;
                    foundY = y;
                }
                waterDepth++;
                if (waterDepth >= 8) {
                    break;
                }
                continue;
            }
            if (color != null && color != MapColor.NONE) {
                if (hitWater) {
                    // floor under water: keep water color, depth already counted
                    break;
                }
                found = color;
                foundY = y;
                break;
            }
        }
        if (hitWater) {
            found = MapColor.WATER;
            foundY = waterY;
        }
        return new Sample(found, foundY, hitWater, waterDepth);
    }

    private static boolean isWater(BlockState state, MapColor color) {
        if (color == MapColor.WATER) {
            return true;
        }
        try {
            return state.getFluidState().is(FluidTags.WATER);
        } catch (Exception e) {
            return false;
        }
    }

    private static int shade(MapColor color, boolean water, int waterDepth,
                             int y, int northY, int px, int py) {
        if (color == null || color == MapColor.NONE) {
            return 0xFF000000;
        }
        int modifier;
        if (water) {
            // Classic water: deeper → darker, plus a 1-pixel dither.
            double d = waterDepth * 0.1 + ((px + py) & 1) * 0.2;
            if (d < 0.5) {
                modifier = MapColor.Brightness.HIGH.modifier;
            } else if (d > 0.9) {
                modifier = MapColor.Brightness.LOWEST.modifier;
            } else {
                modifier = MapColor.Brightness.NORMAL.modifier;
            }
        } else if (y > northY) {
            modifier = MapColor.Brightness.HIGH.modifier;
        } else if (y < northY) {
            modifier = MapColor.Brightness.LOW.modifier;
        } else {
            modifier = MapColor.Brightness.NORMAL.modifier;
        }
        return apply(color, modifier);
    }

    /**
     * ARGB from {@link MapColor#col} × brightness modifier. Built here (not
     * {@code calculateARGBColor}) so the byte order matches
     * {@code NativeImage.setPixel} as used by {@link ItemIcons}.
     */
    private static int apply(MapColor color, int modifier) {
        int rgb = color.col;
        int r = ((rgb >> 16) & 0xFF) * modifier / 255;
        int g = ((rgb >> 8) & 0xFF) * modifier / 255;
        int b = (rgb & 0xFF) * modifier / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static String biomeId(ClientLevel level, BlockPos pos) {
        try {
            Holder<Biome> holder = level.getBiome(pos);
            Optional<ResourceKey<Biome>> key = holder.unwrapKey();
            if (key.isPresent()) {
                return idString(key.get().identifier());
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private static String dimId(ClientLevel level) {
        try {
            return level.dimension().identifier().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }


    /** Minecraft biomes as path ({@code plains}); others as {@code namespace:path}. */
    static String idString(Identifier id) {
        if (id == null) {
            return "unknown";
        }
        if ("minecraft".equals(id.getNamespace())) {
            return id.getPath();
        }
        return id.toString();
    }

    static void atomicWrite(Path out, Path tmp, String json) {
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            try {
                Files.writeString(out, json, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String num(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private static final class Sample {
        final MapColor color;
        final int y;
        final boolean water;
        final int waterDepth;
        Sample(MapColor color, int y, boolean water, int waterDepth) {
            this.color = color;
            this.y = y;
            this.water = water;
            this.waterDepth = waterDepth;
        }
    }
}
