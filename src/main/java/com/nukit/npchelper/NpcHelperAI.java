package com.nukit.npchelper;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.inventory.BaseInventory;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.particle.DestroyBlockParticle;
import cn.nukkit.math.Vector3;
import cn.nukkit.utils.TextFormat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class NpcHelperAI {
    private static final int SEARCH_RADIUS = 12;
    private static final int FOLLOW_DISTANCE = 20;
    private static final int PATH_RADIUS = 16;
    private static final double WALK_SPEED = 0.22;

    private final NpcHelperEntity entity;
    private State state = State.IDLE;
    private Vector3 targetBlock;
    private final Deque<Vector3> path = new ArrayDeque<>();
    private int stuckTicks;

    public NpcHelperAI(NpcHelperEntity entity) {
        this.entity = entity;
    }

    public void tick() {
        Player owner = getOwner();
        if (owner == null || owner.isClosed()) {
            return;
        }

        double distance = entity.distance(owner);
        if (distance > FOLLOW_DISTANCE) {
            entity.setSprinting(true);
            moveTo(owner.getPosition());
            state = State.IDLE;
            if (stuckTicks > 40) {
                entity.teleport(owner.getPosition().add(1, 0, 1));
                stuckTicks = 0;
            }
            return;
        }
        entity.setSprinting(false);

        switch (state) {
            case IDLE -> handleIdle(owner);
            case SEARCHING -> handleSearching();
            case MOVING -> handleMoving();
            case MINING -> handleMining();
        }
    }

    private void handleIdle(Player owner) {
        if (!inventoryHasSpace()) {
            owner.sendMessage(TextFormat.GOLD + "Your helper is ready to unload items.");
            return;
        }
        if (entity.distance(owner) > 4) {
            moveTo(owner.getPosition());
            return;
        }
        state = State.SEARCHING;
    }

    private void handleSearching() {
        Optional<Vector3> target = findNearestResource();
        if (target.isEmpty()) {
            state = State.IDLE;
            return;
        }
        targetBlock = target.get();
        path.clear();
        path.addAll(buildPath(entity.getPosition(), targetBlock));
        state = path.isEmpty() ? State.IDLE : State.MOVING;
    }

    private void handleMoving() {
        if (targetBlock == null) {
            state = State.IDLE;
            return;
        }
        if (entity.distance(targetBlock) <= 1.5) {
            entity.setMotion(new Vector3());
            state = State.MINING;
            return;
        }
        if (path.isEmpty()) {
            state = State.SEARCHING;
            return;
        }
        Vector3 next = path.peek();
        if (entity.distance(next) < 0.7) {
            path.poll();
            stuckTicks = 0;
        } else {
            moveTo(next);
        }
        stuckTicks++;
    }

    private void handleMining() {
        if (targetBlock == null) {
            state = State.IDLE;
            return;
        }
        Level level = entity.getLevel();
        if (!isChunkLoaded(level, targetBlock)) {
            state = State.SEARCHING;
            return;
        }
        Block block = level.getBlock(targetBlock);
        if (!isDesiredBlock(block)) {
            state = State.IDLE;
            return;
        }
        entity.swingArm();
        level.addParticle(new DestroyBlockParticle(block.add(0.5, 0.5, 0.5), block));
        level.setBlock(block, Block.get(BlockID.AIR), true);
        entity.getInventory().addItem(Item.get(block.getId(), 0, 1));
        state = inventoryHasSpace() ? State.SEARCHING : State.IDLE;
    }

    private void moveTo(Vector3 destination) {
        Vector3 direction = destination.subtract(entity.getPosition());
        if (direction.lengthSquared() < 0.01) {
            entity.setMotion(new Vector3());
            return;
        }
        Vector3 motion = direction.normalize().multiply(WALK_SPEED);
        entity.setMotion(motion);
        entity.rotateTowards(destination, 8f);
    }

    private Optional<Vector3> findNearestResource() {
        Level level = entity.getLevel();
        Vector3 origin = entity.getPosition();
        double closest = Double.MAX_VALUE;
        Vector3 best = null;
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;
        boolean chunkLoaded = false;
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -SEARCH_RADIUS; y <= SEARCH_RADIUS; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    Vector3 pos = origin.add(x, y, z);
                    int chunkX = pos.getFloorX() >> 4;
                    int chunkZ = pos.getFloorZ() >> 4;
                    if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
                        chunkLoaded = level.isChunkLoaded(chunkX, chunkZ);
                        lastChunkX = chunkX;
                        lastChunkZ = chunkZ;
                    }
                    if (!chunkLoaded) {
                        continue;
                    }
                    Block block = level.getBlock(pos);
                    if (isDesiredBlock(block)) {
                        double distance = origin.distance(pos);
                        if (distance < closest) {
                            closest = distance;
                            best = new Vector3(block.getX() + 0.5, block.getY(), block.getZ() + 0.5);
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private List<Vector3> buildPath(Vector3 start, Vector3 rawTarget) {
        Vector3 startNode = toGrid(start);
        Vector3 targetNode = resolveWalkableTarget(rawTarget);
        if (targetNode == null) {
            return List.of();
        }
        Level level = entity.getLevel();
        if (!isChunkLoaded(level, startNode) || !isChunkLoaded(level, targetNode)) {
            return List.of();
        }

        int minX = startNode.getFloorX() - PATH_RADIUS;
        int maxX = startNode.getFloorX() + PATH_RADIUS;
        int minY = startNode.getFloorY() - 4;
        int maxY = startNode.getFloorY() + 4;
        int minZ = startNode.getFloorZ() - PATH_RADIUS;
        int maxZ = startNode.getFloorZ() + PATH_RADIUS;

        Deque<Vector3> queue = new ArrayDeque<>();
        Map<String, Vector3> cameFrom = new HashMap<>();
        Map<String, Boolean> visited = new HashMap<>();
        queue.add(startNode);
        visited.put(key(startNode), true);

        int[][] dirs = {
                {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
                {0, 1, 0}, {0, -1, 0}
        };

        while (!queue.isEmpty()) {
            Vector3 current = queue.poll();
            if (current.equals(targetNode)) {
                return reconstructPath(cameFrom, current);
            }
            for (int[] dir : dirs) {
                Vector3 next = current.add(dir[0], dir[1], dir[2]);
                if (next.getFloorX() < minX || next.getFloorX() > maxX
                        || next.getFloorY() < minY || next.getFloorY() > maxY
                        || next.getFloorZ() < minZ || next.getFloorZ() > maxZ) {
                    continue;
                }
                String key = key(next);
                if (visited.containsKey(key)) {
                    continue;
                }
                if (!isWalkable(level, next)) {
                    continue;
                }
                visited.put(key, true);
                cameFrom.put(key, current);
                queue.add(next);
            }
        }
        return List.of();
    }

    private List<Vector3> reconstructPath(Map<String, Vector3> cameFrom, Vector3 current) {
        List<Vector3> pathList = new ArrayList<>();
        pathList.add(toCenter(current));
        String currentKey = key(current);
        while (cameFrom.containsKey(currentKey)) {
            Vector3 prev = cameFrom.get(currentKey);
            pathList.add(0, toCenter(prev));
            currentKey = key(prev);
        }
        return pathList;
    }

    private Vector3 resolveWalkableTarget(Vector3 rawTarget) {
        if (rawTarget == null) {
            return null;
        }
        Vector3 target = toGrid(rawTarget);
        Level level = entity.getLevel();
        if (isWalkable(level, target)) {
            return target;
        }
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] offset : offsets) {
            Vector3 candidate = target.add(offset[0], offset[1], offset[2]);
            if (isWalkable(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Vector3 toGrid(Vector3 vector) {
        return new Vector3(vector.getFloorX(), vector.getFloorY(), vector.getFloorZ());
    }

    private Vector3 toCenter(Vector3 vector) {
        return new Vector3(vector.getFloorX() + 0.5, vector.getFloorY(), vector.getFloorZ() + 0.5);
    }

    private String key(Vector3 vector) {
        return vector.getFloorX() + ":" + vector.getFloorY() + ":" + vector.getFloorZ();
    }

    private boolean isWalkable(Level level, Vector3 position) {
        if (!isChunkLoaded(level, position)) {
            return false;
        }
        Block head = level.getBlock(position.getFloorX(), position.getFloorY() + 1, position.getFloorZ());
        Block body = level.getBlock(position.getFloorX(), position.getFloorY(), position.getFloorZ());
        Block feet = level.getBlock(position.getFloorX(), position.getFloorY() - 1, position.getFloorZ());
        return body.isTransparent() && head.isTransparent() && feet.isSolid();
    }

    private boolean isDesiredBlock(Block block) {
        return block.getId() == BlockID.DIAMOND_ORE || block.getId() == BlockID.LOG;
    }

    private Player getOwner() {
        UUID ownerId = entity.getOwnerId();
        if (ownerId == null) {
            return null;
        }
        return Server.getInstance().getPlayer(ownerId).orElse(null);
    }

    private boolean inventoryHasSpace() {
        BaseInventory inventory = entity.getInventory();
        return inventory.firstEmpty() != -1;
    }

    private boolean isChunkLoaded(Level level, Vector3 position) {
        return level.isChunkLoaded(position.getFloorX() >> 4, position.getFloorZ() >> 4);
    }

    enum State {
        IDLE,
        SEARCHING,
        MOVING,
        MINING
    }
}
