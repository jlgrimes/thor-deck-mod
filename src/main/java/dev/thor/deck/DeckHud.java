package dev.thor.deck;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.biome.Biome;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * Writes {@code thor_deck/hud.json} every 5 client ticks from {@link LocalPlayer}.
 */
public final class DeckHud {
    private static final int PERIOD_TICKS = 5;

    private static int ticks = 0;
    private static int seq = 0;

    private DeckHud() {}

    public static void tick(Minecraft client, LocalPlayer player, Path dir) {
        try {
            if (player == null || client.level == null) {
                return;
            }
            if (++ticks < PERIOD_TICKS) {
                return;
            }
            ticks = 0;
            String json = build(client, player);
            DeckMap.atomicWrite(dir.resolve("hud.json"), dir.resolve("hud.json.tmp"), json);
        } catch (Exception ignored) {
        }
    }

    private static String build(Minecraft client, LocalPlayer player) {
        ClientLevel level = client.level;
        seq++;
        float hp = player.getHealth();
        float maxHp = player.getMaxHealth();
        float absorption = 0.0f;
        try {
            absorption = player.getAbsorptionAmount();
        } catch (Exception ignored) {
        }
        int hunger = 0;
        float saturation = 0.0f;
        try {
            FoodData food = player.getFoodData();
            hunger = food.getFoodLevel();
            saturation = food.getSaturationLevel();
        } catch (Exception ignored) {
        }
        int air = 0;
        try {
            air = player.getAirSupply();
        } catch (Exception ignored) {
        }
        float xp = 0.0f;
        int levelNum = 0;
        try {
            xp = player.experienceProgress;
            levelNum = player.experienceLevel;
        } catch (Exception ignored) {
        }
        int armor = 0;
        try {
            armor = player.getArmorValue();
        } catch (Exception ignored) {
        }

        String biome = "unknown";
        String dim = "unknown";
        String time = "day";
        long dayTime = 0;
        String weather = "clear";
        try {
            biome = biomeId(level, player);
            dim = level.dimension().identifier().toString();
            dayTime = level.getDayTime() % 24000L;
            if (dayTime < 0) {
                dayTime += 24000L;
            }
            time = timeOf(dayTime);
            if (level.isThundering()) {
                weather = "thunder";
            } else if (level.isRaining()) {
                weather = "rain";
            }
        } catch (Exception ignored) {
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"seq\":" ).append(seq)
                .append(",\"hp\":" ).append(num(hp))
                .append(",\"maxHp\":" ).append(num(maxHp))
                .append(",\"absorption\":" ).append(num(absorption))
                .append(",\"hunger\":" ).append(hunger)
                .append(",\"saturation\":" ).append(num(saturation))
                .append(",\"air\":" ).append(air)
                .append(",\"xp\":" ).append(num(xp))
                .append(",\"level\":" ).append(levelNum)
                .append(",\"armor\":" ).append(armor)
                .append(",\"x\":" ).append(num(player.getX()))
                .append(",\"y\":" ).append(num(player.getY()))
                .append(",\"z\":" ).append(num(player.getZ()))
                .append(",\"yaw\":" ).append(num(player.getYRot()))
                .append(",\"pitch\":" ).append(num(player.getXRot()))
                .append(",\"biome\":\"").append(DeckMap.escape(biome)).append('"')
                .append(",\"dim\":\"").append(DeckMap.escape(dim)).append('"')
                .append(",\"time\":\"").append(time).append('"')
                .append(",\"dayTime\":" ).append(dayTime)
                .append(",\"weather\":\"").append(weather).append('"')
                .append(",\"effects\":[");
        boolean first = true;
        try {
            Collection<MobEffectInstance> effects = player.getActiveEffects();
            for (MobEffectInstance effect : effects) {
                if (effect == null) {
                    continue;
                }
                String id = effectId(effect);
                int amp = 0;
                int secs = 0;
                try {
                    amp = effect.getAmplifier();
                    int dur = effect.getDuration();
                    if (dur > 0) {
                        secs = dur / 20;
                    }
                } catch (Exception ignored) {
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"id\":\"").append(DeckMap.escape(id)).append('"')
                        .append(",\"amp\":" ).append(amp)
                        .append(",\"secs\":" ).append(secs)
                        .append('}');
            }
        } catch (Exception ignored) {
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String timeOf(long t) {
        if (t >= 12000L && t < 13000L) {
            return "sunset";
        }
        if (t >= 13000L && t < 23000L) {
            return "night";
        }
        if (t >= 23000L || t < 1000L) {
            return "sunrise";
        }
        return "day";
    }

    private static String biomeId(ClientLevel level, LocalPlayer player) {
        try {
            Holder<Biome> holder = level.getBiome(player.blockPosition());
            Optional<ResourceKey<Biome>> key = holder.unwrapKey();
            if (key.isPresent()) {
                return DeckMap.idString(key.get().identifier());
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private static String effectId(MobEffectInstance effect) {
        try {
            Holder<MobEffect> holder = effect.getEffect();
            Optional<ResourceKey<MobEffect>> key = holder.unwrapKey();
            if (key.isPresent()) {
                return key.get().identifier().toString();
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private static String num(double v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
