package com.mcsaveeditor.command;

import com.mcsaveeditor.network.ModNetworking;
import com.mcsaveeditor.network.packet.OpenEditorPacket;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.network.PacketDistributor;

public final class ModCommands {
    private ModCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("mcsaveeditor")
                .requires(source -> source.hasPermission(3))
                .then(Commands.literal("open").executes(context -> openEditor(context.getSource()))));
    }

    private static int openEditor(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ChunkPos center = player.chunkPosition();
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenEditorPacket(center.x, center.z));
        return 1;
    }
}
