package com.nukit.npchelper;

import cn.nukkit.Player;
import cn.nukkit.entity.EntityCreature;
import cn.nukkit.entity.passive.EntityVillager;
import cn.nukkit.inventory.SimpleInventory;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.AnimateEntityPacket;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.utils.TextFormat;

import java.util.UUID;

public class NpcHelperEntity extends EntityVillager {
    private static final int INVENTORY_SIZE = 27;

    private final SimpleInventory inventory = new SimpleInventory(INVENTORY_SIZE);
    private final NpcHelperAI ai;
    private UUID ownerId;

    public NpcHelperEntity(Level level, CompoundTag nbt) {
        super(level, nbt);
        this.ai = new NpcHelperAI(this);
    }

    public static NpcHelperEntity spawn(Level level, Player owner, Position position) {
        CompoundTag nbt = EntityCreature.getDefaultNBT(position);
        NpcHelperEntity entity = new NpcHelperEntity(level, nbt);
        entity.ownerId = owner.getUniqueId();
        entity.spawnToAll();
        entity.setNameTagVisible(true);
        entity.setNameTagAlwaysVisible(true);
        entity.setNameTag(TextFormat.AQUA + owner.getName() + "'s Helper");
        entity.setHealth(20);
        entity.setMaxHealth(20);
        entity.setImmobile(false);
        return entity;
    }

    public void tickAI() {
        if (isClosed()) {
            return;
        }
        ai.tick();
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public boolean isOwner(Player player) {
        return ownerId != null && ownerId.equals(player.getUniqueId());
    }

    public SimpleInventory getInventory() {
        return inventory;
    }

    public void openInventory(Player player) {
        player.addWindow(inventory);
    }

    public void swingArm() {
        AnimateEntityPacket packet = new AnimateEntityPacket();
        packet.entityRuntimeId = this.getId();
        packet.action = AnimateEntityPacket.ACTION_SWING_ARM;
        broadcastPacket(packet);
    }

    public void broadcastPacket(DataPacket packet) {
        this.getLevel().addChunkPacket(this.getChunkX(), this.getChunkZ(), packet);
    }

    public void rotateTowards(Vector3 vector, float maxYawChange) {
        Vector3 direction = vector.subtract(this.getPosition());
        if (direction.lengthSquared() < 0.001) {
            return;
        }
        double targetYaw = Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        float currentYaw = this.getYaw();
        float delta = wrapDegrees((float) (targetYaw - currentYaw));
        float clamped = Math.max(-maxYawChange, Math.min(maxYawChange, delta));
        this.setRotation(currentYaw + clamped, this.getPitch());
        this.setHeadYaw(this.getYaw());
    }

    private float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }
}
