package com.nukit.npchelper;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerInteractEntityEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.level.Level;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.Task;
import cn.nukkit.utils.TextFormat;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NpcHelperPlugin extends PluginBase implements Listener {
    private final Map<UUID, NpcHelperEntity> helpers = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        Entity.registerEntity("NpcHelperVillager", NpcHelperEntity.class);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleRepeatingTask(this, new Task() {
            @Override
            public void onRun(int currentTick) {
                helpers.values().forEach(NpcHelperEntity::tickAI);
            }
        }, 10);
    }

    @Override
    public void onDisable() {
        helpers.values().forEach(Entity::close);
        helpers.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(TextFormat.RED + "Only players can use this command.");
            return true;
        }

        UUID ownerId = player.getUniqueId();
        NpcHelperEntity existing = helpers.get(ownerId);
        if (existing != null && !existing.isClosed()) {
            existing.close();
            helpers.remove(ownerId);
            player.sendMessage(TextFormat.YELLOW + "NPC helper removed.");
            return true;
        }

        Level level = player.getLevel();
        NpcHelperEntity helper = NpcHelperEntity.spawn(level, player, player.getPosition().add(1, 0, 1));
        helpers.put(ownerId, helper);
        player.sendMessage(TextFormat.GREEN + "NPC helper spawned.");
        return true;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEntityEvent event) {
        if (event.getEntity() instanceof NpcHelperEntity helper) {
            if (!helper.isOwner(event.getPlayer())) {
                event.getPlayer().sendMessage(TextFormat.RED + "This helper belongs to someone else.");
                return;
            }
            helper.openInventory(event.getPlayer());
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onHelperDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof NpcHelperEntity) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Optional.ofNullable(helpers.remove(event.getPlayer().getUniqueId()))
                .ifPresent(Entity::close);
    }
}
