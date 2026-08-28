package dev.thor.deck;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.AtlasManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Exports a crisp 16x16 (or native-resolution) PNG per unique inventory item
 * into {@code thor_deck/icons/<stem>.png}.
 *
 * <p>Approach (1.21.11 official mappings): framebuffer capture via
 * {@code GuiGraphics.renderItem} is too brittle after the 1.21.6+ GPU rewrite
 * (RenderPass / GpuTexture, no glReadPixels) and on Pojav/GLES. Instead we
 * extract the item's particle/sprite pixels:
 * <ol>
 *   <li>Look up the sprite on the items (then blocks) atlas, then load the
 *       original PNG from the resource manager using {@link SpriteContents#name()}.</li>
 *   <li>Fall back to {@code textures/item/<path>.png} and {@code textures/block/<path>.png}.</li>
 *   <li>Last resort: copy pixels out of {@code SpriteContents.originalImage}
 *       (private, via reflection) or write a generated 16x16 placeholder.
 * </ol>
 * All copies are nearest-neighbor. Existing files are not rewritten.
 */
public final class ItemIcons {
    private static final Set<String> WRITTEN = new HashSet<>();
    private static Field originalImageField;

    private ItemIcons() {}

    public static String stemFor(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id.getPath().replace('/', '_').replace('\\', '_');
        String stem;
        if ("minecraft".equals(id.getNamespace())) {
            stem = path;
        } else {
            stem = id.getNamespace() + "__" + path;
        }
        if (stack.get(DataComponents.CUSTOM_NAME) != null) {
            stem = stem + "_" + Integer.toHexString(stack.getHoverName().getString().hashCode());
        }
        StringBuilder sb = new StringBuilder(stem.length());
        for (int i = 0; i < stem.length(); i++) {
            char c = stem.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.'
                    ? c : '_');
        }
        return sb.toString();
    }

    public static void export(Minecraft client, ItemStack stack, Path iconDir, String stem) {
        if (stem == null || stem.isEmpty() || WRITTEN.contains(stem)) {
            return;
        }
        Path out = iconDir.resolve(stem + ".png");
        if (Files.exists(out)) {
            WRITTEN.add(stem);
            return;
        }
        try {
            Files.createDirectories(iconDir);
        } catch (Exception e) {
            return;
        }
        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        NativeImage image = null;
        try {
            image = fromAtlas(client, itemId);
            if (image == null) {
                image = fromResources(client.getResourceManager(), itemId);
            }
            if (image == null) {
                image = placeholder(itemId);
            }
            if (image == null) {
                return;
            }
            Path tmp = iconDir.resolve(stem + ".png.tmp");
            image.writeToFile(tmp);
            try {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            }
            WRITTEN.add(stem);
        } catch (Exception e) {
            System.err.println("[ThorDeck] icon export failed for " + itemId + ": " + e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private static NativeImage fromAtlas(Minecraft client, Identifier itemId) {
        try {
            AtlasManager atlases = client.getAtlasManager();
            NativeImage img = spritePixels(atlases, AtlasIds.ITEMS, itemId, true);
            if (img != null) {
                return img;
            }
            return spritePixels(atlases, AtlasIds.BLOCKS, itemId, false);
        } catch (Exception e) {
            return null;
        }
    }

    private static NativeImage spritePixels(AtlasManager atlases, Identifier atlasId,
                                            Identifier itemId, boolean itemAtlas) {
        TextureAtlas atlas = atlases.getAtlasOrThrow(atlasId);
        TextureAtlasSprite missing = atlas.missingSprite();
        String path = itemId.getPath();
        String[] keys = itemAtlas
                ? new String[]{"item/" + path, path}
                : new String[]{"block/" + path, path};
        for (String key : keys) {
            Identifier spriteId = Identifier.fromNamespaceAndPath(itemId.getNamespace(), key);
            TextureAtlasSprite sprite = atlas.getSprite(spriteId);
            if (sprite == null || sprite == missing) {
                continue;
            }
            NativeImage fromFile = loadSpritePng(Minecraft.getInstance().getResourceManager(), sprite);
            if (fromFile != null) {
                return fromFile;
            }
            NativeImage copied = copySpriteImage(sprite);
            if (copied != null) {
                return copied;
            }
        }
        return null;
    }

    private static NativeImage loadSpritePng(ResourceManager rm, TextureAtlasSprite sprite) {
        try {
            Identifier name = sprite.contents().name();
            Identifier tex = Identifier.fromNamespaceAndPath(
                    name.getNamespace(), "textures/" + name.getPath() + ".png");
            return readFirstFrame(rm, tex, sprite.contents().width(), sprite.contents().height());
        } catch (Exception e) {
            return null;
        }
    }

    private static NativeImage fromResources(ResourceManager rm, Identifier itemId) {
        String path = itemId.getPath();
        String ns = itemId.getNamespace();
        NativeImage img = readPng(rm, Identifier.fromNamespaceAndPath(ns, "textures/item/" + path + ".png"));
        if (img != null) {
            return cropFirstFrame(img);
        }
        img = readPng(rm, Identifier.fromNamespaceAndPath(ns, "textures/block/" + path + ".png"));
        if (img != null) {
            return cropFirstFrame(img);
        }
        return null;
    }

    private static NativeImage readFirstFrame(ResourceManager rm, Identifier tex, int fw, int fh) {
        NativeImage full = readPng(rm, tex);
        if (full == null) {
            return null;
        }
        if (fw <= 0 || fh <= 0 || (full.getWidth() == fw && full.getHeight() == fh)) {
            return full;
        }
        try {
            NativeImage frame = copyRect(full, Math.min(fw, full.getWidth()), Math.min(fh, full.getHeight()));
            full.close();
            return frame;
        } catch (Exception e) {
            return full;
        }
    }

    private static NativeImage readPng(ResourceManager rm, Identifier tex) {
        try {
            Optional<Resource> res = rm.getResource(tex);
            if (res.isEmpty()) {
                return null;
            }
            try (InputStream in = res.get().open()) {
                return NativeImage.read(in);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static NativeImage cropFirstFrame(NativeImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        // Animated textures are stored as a vertical (or grid) strip; take the top square.
        if (h > w) {
            NativeImage frame = copyRect(src, w, w);
            src.close();
            return frame;
        }
        return src;
    }

    private static NativeImage copySpriteImage(TextureAtlasSprite sprite) {
        try {
            SpriteContents contents = sprite.contents();
            NativeImage src = originalImage(contents);
            if (src == null) {
                return null;
            }
            int w = Math.min(contents.width(), src.getWidth());
            int h = Math.min(contents.height(), src.getHeight());
            return copyRect(src, w, h);
        } catch (Exception e) {
            return null;
        }
    }

    private static NativeImage originalImage(SpriteContents contents) {
        try {
            if (originalImageField == null) {
                Field f = SpriteContents.class.getDeclaredField("originalImage");
                f.setAccessible(true);
                originalImageField = f;
            }
            Object v = originalImageField.get(contents);
            return v instanceof NativeImage img ? img : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Nearest-neighbor copy of the top-left w x h pixels. Does not close {@code src}. */
    private static NativeImage copyRect(NativeImage src, int w, int h) {
        NativeImage dst = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setPixel(x, y, src.getPixel(x, y));
            }
        }
        return dst;
    }

    private static NativeImage placeholder(Identifier itemId) {
        NativeImage img = new NativeImage(16, 16, false);
        int hash = itemId.toString().hashCode();
        int rgb = 0xFF000000 | (hash & 0x00FFFFFF);
        int dark = 0xFF000000 | ((hash >>> 8) & 0x00FFFFFF);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean checker = ((x >> 3) ^ (y >> 3)) == 0;
                img.setPixel(x, y, checker ? rgb : dark);
            }
        }
        return img;
    }
}
