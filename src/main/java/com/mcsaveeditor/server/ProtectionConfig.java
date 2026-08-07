package com.mcsaveeditor.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-job protection settings: which categories are preserved and which blocks
 * are explicitly excluded.
 *
 * <p>Exclusion has the highest priority: a block in the excluded set is never
 * preserved, even if it belongs to an enabled category.</p>
 */
public final class ProtectionConfig {

    private final Set<String> enabledCategories = new HashSet<>();
    private final Set<ResourceLocation> excludedBlocks = new HashSet<>();

    private ProtectionConfig() {
    }

    public static ProtectionConfig defaults() {
        ProtectionConfig config = new ProtectionConfig();
        for (ProtectionCategories.Category category : ProtectionCategories.CATEGORIES) {
            if (category.defaultOn()) {
                config.enabledCategories.add(category.id());
            }
        }
        return config;
    }

    public static ProtectionConfig empty() {
        return new ProtectionConfig();
    }

    public boolean isEnabled(ProtectionCategories.Category category) {
        return enabledCategories.contains(category.id());
    }

    public boolean isExcluded(BlockState state) {
        return excludedBlocks.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    public ProtectionConfig enable(String categoryId) {
        enabledCategories.add(categoryId);
        return this;
    }

    public ProtectionConfig disable(String categoryId) {
        enabledCategories.remove(categoryId);
        return this;
    }

    public ProtectionConfig exclude(ResourceLocation blockId) {
        excludedBlocks.add(blockId);
        return this;
    }

    /**
     * Builds a config from a list of category ids sent by the client, ignoring
     * unknown ids. When the list is empty the default categories are kept.
     */
    public static ProtectionConfig fromCategories(List<String> categoryIds) {
        if (categoryIds.isEmpty()) {
            return defaults();
        }
        ProtectionConfig config = empty();
        for (String id : categoryIds) {
            boolean known = false;
            for (ProtectionCategories.Category category : ProtectionCategories.CATEGORIES) {
                if (category.id().equals(id)) {
                    known = true;
                    break;
                }
            }
            if (known) {
                config.enabledCategories.add(id);
            }
        }
        return config;
    }

    public Set<String> enabledCategories() {
        return enabledCategories;
    }

    public Set<ResourceLocation> excludedBlocks() {
        return excludedBlocks;
    }
}
