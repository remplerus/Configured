package com.mrcrayfish.configured.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mrcrayfish.configured.Config;
import com.mrcrayfish.configured.Configured;
import com.mrcrayfish.configured.Reference;
import com.mrcrayfish.configured.api.ConfigType;
import com.mrcrayfish.configured.api.ConfiguredHelper;
import com.mrcrayfish.configured.api.IModConfig;
import com.mrcrayfish.configured.client.screen.IBackgroundTexture;
import com.mrcrayfish.configured.client.screen.ListMenuScreen;
import com.mrcrayfish.configured.client.util.OptiFineHelper;
import com.mrcrayfish.configured.impl.forge.ForgeConfig;
import com.mrcrayfish.configured.impl.simple.SimpleConfigManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ClientRegistry;
import net.minecraftforge.client.ConfigGuiHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.gui.ModListScreen;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.forgespi.language.IModInfo;
import org.lwjgl.glfw.GLFW;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Author: MrCrayfish
 */
@Mod.EventBusSubscriber(modid = Reference.MOD_ID, value = Dist.CLIENT)
public class ClientHandler
{
    public static final KeyMapping KEY_OPEN_MOD_LIST = new KeyMapping("key.configured.open_mod_list", -1, "key.categories.configured");

    public static void init()
    {
        ClientRegistry.registerKeyBinding(KEY_OPEN_MOD_LIST);
        ListMenuScreen.registerTooltipFactory();
        generateConfigFactories();
    }

    // This is where the magic happens
    private static void generateConfigFactories()
    {
        Configured.LOGGER.info("Creating config GUI factories...");
        ModList.get().forEachModContainer((modId, container) ->
        {
            // Ignore mods that already implement their own custom factory
            if(container.getCustomExtension(ConfigGuiHandler.ConfigGuiFactory.class).isPresent() && !Config.CLIENT.forceConfiguredMenu.get())
                return;

            Map<ConfigType, Set<IModConfig>> modConfigMap = createConfigMap(container);
            if(!modConfigMap.isEmpty()) // Only add if at least one config exists
            {
                Configured.LOGGER.info("Registering config factory for mod {}. Found {} client config(s) and {} common config(s)", modId, modConfigMap.getOrDefault(ModConfig.Type.CLIENT, Collections.emptySet()).size(), modConfigMap.getOrDefault(ModConfig.Type.COMMON, Collections.emptySet()).size());
                String displayName = container.getModInfo().getDisplayName();
                ResourceLocation backgroundTexture = getBackgroundTexture(container.getModInfo());
                container.registerExtensionPoint(ConfigGuiHandler.ConfigGuiFactory.class, () -> new ConfigGuiHandler.ConfigGuiFactory((mc, screen) -> {
                    return ConfiguredHelper.createSelectionScreen(screen, new TextComponent(displayName), modConfigMap, backgroundTexture);
                }));
            }
        });
    }

    private static EnumMap<ModConfig.Type, Set<ModConfig>> getConfigSets()
    {
        return ObfuscationReflectionHelper.getPrivateValue(ConfigTracker.class, ConfigTracker.INSTANCE, "configSets");
    }

    public static Map<ConfigType, Set<IModConfig>> createConfigMap(ModContainer container)
    {
        Map<ConfigType, Set<IModConfig>> modConfigMap = new HashMap<>();

        // Add Forge configurations
        addForgeConfigSetToMap(container, ModConfig.Type.CLIENT, ConfigType.CLIENT, modConfigMap);
        addForgeConfigSetToMap(container, ModConfig.Type.COMMON, ConfigType.UNIVERSAL, modConfigMap);
        addForgeConfigSetToMap(container, ModConfig.Type.SERVER, ConfigType.WORLD_SYNC, modConfigMap);

        // Add SimpleConfig configurations
        SimpleConfigManager.getInstance().getConfigs().stream().filter(entry -> entry.getModId().equals(container.getModId())).forEach(entry -> {
            modConfigMap.computeIfAbsent(entry.getType(), type -> new LinkedHashSet<>()).add(entry);
        });

        return modConfigMap;
    }

    private static void addForgeConfigSetToMap(ModContainer container, ModConfig.Type type, ConfigType configType, Map<ConfigType, Set<IModConfig>> configMap)
    {
        /* Optifine basically breaks Forge's client config, so it's simply not added */
        if(type == ModConfig.Type.CLIENT && OptiFineHelper.isLoaded() && container.getModId().equals("forge"))
        {
            Configured.LOGGER.info("Ignoring Forge's client config since OptiFine was detected");
            return;
        }

        Set<ModConfig> configSet = getConfigSets().get(type);
        Set<IModConfig> filteredConfigSets = configSet.stream().filter(config -> config.getModId().equals(container.getModId())).map(ForgeConfig::new).collect(Collectors.toSet());
        if(!filteredConfigSets.isEmpty())
        {
            configMap.put(configType, filteredConfigSets);
        }
    }

    private static ResourceLocation getBackgroundTexture(IModInfo info)
    {
        String configBackground = (String) info.getModProperties().get("configuredBackground");

        if(configBackground != null)
        {
            return new ResourceLocation(configBackground);
        }

        if(info instanceof ModInfo)
        {
            // Fallback to old method to getting config background (since mods might not have updated)
            Optional<String> optional = ((ModInfo) info).getConfigElement("configBackground");
            if(optional.isPresent())
            {
                return new ResourceLocation(optional.get());
            }
        }

        return Screen.BACKGROUND_LOCATION;
    }

    /**
     * Linked via ASM. Do not delete!
     */
    @SuppressWarnings("unused")
    public static void updateAbstractListTexture(AbstractSelectionList<?> list)
    {
        if(list instanceof IBackgroundTexture)
        {
            RenderSystem.setShaderTexture(0, ((IBackgroundTexture) list).getBackgroundTexture());
        }
    }

    /**
     * Linked via ASM. Do not delete!
     */
    @SuppressWarnings("unused")
    public static void updateScreenTexture(Screen screen)
    {
        if(screen instanceof IBackgroundTexture)
        {
            RenderSystem.setShaderTexture(0, ((IBackgroundTexture) screen).getBackgroundTexture());
        }
    }

    @SubscribeEvent
    public static void onKeyPress(InputEvent.KeyInputEvent event)
    {
        if(event.getAction() == GLFW.GLFW_PRESS && KEY_OPEN_MOD_LIST.isDown())
        {
            Minecraft minecraft = Minecraft.getInstance();
            if(minecraft.player == null)
                return;
            Screen oldScreen = minecraft.screen;
            minecraft.setScreen(new ModListScreen(oldScreen));
        }
    }

    @SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggedOutEvent event)
    {
        SimpleConfigManager.getInstance().onClientDisconnect(event.getConnection());
    }
}
