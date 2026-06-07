package com.example.fabricmod.block;

import com.example.fabricmod.FabricMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/**
 * Registry class for custom blocks.
 */
public class ModBlocks {
    /**
     * Register a block and its corresponding BlockItem.
     */
    private static Block register(String name, Block block) {
        // Register the block
        Block registeredBlock = Registry.register(
                Registries.BLOCK, FabricMod.id(name), block
        );
        // Register the block item so it can be obtained in-game
        Registry.register(
                Registries.ITEM, FabricMod.id(name),
                new BlockItem(registeredBlock, new Item.Settings())
        );
        return registeredBlock;
    }

    /**
     * Call this from FabricMod.onInitialize() to register everything.
     */
    public static void register() {
        FabricMod.LOGGER.info("Registering blocks for {}", FabricMod.MOD_ID);

        // === Register your blocks here ===
        // Example:
        // public static final Block RUBY_BLOCK = register("ruby_block",
        //         new Block(AbstractBlock.Settings.copy(Blocks.IRON_BLOCK)));
    }
}
