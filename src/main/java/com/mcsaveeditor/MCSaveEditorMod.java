package com.mcsaveeditor;

import com.mcsaveeditor.client.ClientBootstrap;
import com.mcsaveeditor.client.EditorPrefs;
import com.mcsaveeditor.command.ModCommands;
import com.mcsaveeditor.network.ModNetworking;
import com.mcsaveeditor.server.ServerChunkEraseService;
import com.mcsaveeditor.server.ServerChunkScanService;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(MCSaveEditorMod.MOD_ID)
public class MCSaveEditorMod {
    public static final String MOD_ID = "mcsaveeditor";

    public MCSaveEditorMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.addListener(ModCommands::register);
        MinecraftForge.EVENT_BUS.addListener(ServerChunkEraseService::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(ServerChunkScanService::onServerTick);

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> EditorPrefs::register);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientBootstrap::init);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetworking::register);
    }
}
