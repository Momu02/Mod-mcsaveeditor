package com.mcsaveeditor.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
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

    public Set<String> enabledCategories() {
        return enabledCategories;
    }

    public Set<ResourceLocation> excludedBlocks() {
        return excludedBlocks;
    }
}
