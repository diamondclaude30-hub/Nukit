package com.nukit.npchelper;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.particle.DestroyBlockParticle;
import cn.nukkit.math.Vector3;
import cn.nukkit.scheduler.AsyncTask;
import cn.nukkit.utils.TextFormat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class NpcHelperAI {
    private static final int SEARCH_RADIUS = 12;
    private static final int FOLLOW_DISTANCE = 20;
    private static final int MAX_INVENTORY = 27;
    private static final int PATH_SCAN_RADIUS = 16;

    private final NpcHelperEntity entity;
    private State state = State.IDLE;
    private Vector3 targetBlock;
    private final Deque<Vector3> path = new ArrayDeque<>();
    private int stuckTicks;
    private boolean pathRequestPending;

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
            case NAVIGATING -> handleNavigating();
            case MINING -> handleMining();
            case DROPPING -> handleDropping(owner);
        }
    }

    private void handleIdle(Player owner) {
        if (!inventoryHasSpace()) {
            state = State.DROPPING;
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
        if (target.isPresent()) {
            targetBlock = target.get();
            state = State.NAVIGATING;
            requestPath();
        } else {
            state = State.IDLE;
        }
    }

    private void handleNavigating() {
        if (targetBlock == null) {
            state = State.IDLE;
            return;
        }
        if (entity.distance(targetBlock) <= 1.5) {
            entity.setMotion(new Vector3());
            state = State.MINING;
            return;
        }
        if (path.isEmpty() && !pathRequestPending) {
            requestPath();
        }
        if (!path.isEmpty()) {
            Vector3 next = path.peek();
            if (entity.distance(next) < 0.7) {
                path.poll();
                stuckTicks = 0;
            } else {
                moveTo(next);
            }
        } else {
            stuckTicks++;
        }
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
        state = inventoryHasSpace() ? State.SEARCHING : State.DROPPING;
    }

    private void handleDropping(Player owner) {
        if (entity.distance(owner) > 2.5) {
            moveTo(owner.getPosition());
            return;
        }
        owner.sendMessage(TextFormat.GOLD + "Your helper is ready to unload items.");
        state = State.IDLE;
    }

    private void moveTo(Vector3 destination) {
        Vector3 direction = destination.subtract(entity.getPosition());
        if (direction.lengthSquared() < 0.01) {
            entity.setMotion(new Vector3());
            return;
        }
        Vector3 motion = direction.normalize().multiply(0.22);
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

    private boolean isDesiredBlock(Block block) {
        return block.getId() == BlockID.DIAMOND_ORE || block.getId() == BlockID.LOG;
    }

    private Player getOwner() {
        UUID ownerId = entity.getOwnerId();
        return ownerId == null ? null : Server.getInstance().getPlayer(ownerId);
    }

    private void requestPath() {
        if (pathRequestPending || targetBlock == null) {
            return;
        }
        pathRequestPending = true;
        Level level = entity.getLevel();
        Vector3 start = entity.getPosition();
        Vector3 target = targetBlock;
        if (!isChunkLoaded(level, start) || !isChunkLoaded(level, target)) {
            pathRequestPending = false;
            return;
        }
        WalkableSnapshot snapshot = WalkableSnapshot.capture(level, start, PATH_SCAN_RADIUS);
        Server.getInstance().getScheduler().scheduleAsyncTask(new AsyncTask() {
            @Override
            public void onRun() {
                Vector3 walkableTarget = resolveWalkableTarget(snapshot, target);
                List<Vector3> computed = PathfindingTask.computePath(snapshot, start, walkableTarget);
                setResult(computed);
            }

            @Override
            public void onCompletion(Server server) {
                List<Vector3> result = getResult();
                path.clear();
                if (result != null) {
                    path.addAll(result);
                }
                pathRequestPending = false;
            }
        });
    }

    private Vector3 resolveWalkableTarget(WalkableSnapshot snapshot, Vector3 rawTarget) {
        if (rawTarget == null) {
            return null;
        }
        Vector3 target = rawTarget.clone();
        if (snapshot.isWalkable(target)) {
            return target;
        }
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] offset : offsets) {
            Vector3 candidate = target.add(offset[0], offset[1], offset[2]);
            if (snapshot.isWalkable(candidate)) {
                return candidate;
            }
        }
        return target;
    }

    enum State {
        IDLE,
        SEARCHING,
        NAVIGATING,
        MINING,
        DROPPING
    }

    public record WalkableSnapshot(int radius, int centerX, int centerY, int centerZ, boolean[][][] walkable) {
        static WalkableSnapshot capture(Level level, Vector3 center, int radius) {
            int size = radius * 2 + 1;
            boolean[][][] walkable = new boolean[size][size][size];
            int baseX = center.getFloorX() - radius;
            int baseY = center.getFloorY() - radius;
            int baseZ = center.getFloorZ() - radius;
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    for (int z = 0; z < size; z++) {
                        int worldX = baseX + x;
                        int worldY = baseY + y;
                        int worldZ = baseZ + z;
                        if (!level.isChunkLoaded(worldX >> 4, worldZ >> 4)) {
                            walkable[x][y][z] = false;
                            continue;
                        }
                        Block head = level.getBlock(worldX, worldY + 1, worldZ);
                        Block body = level.getBlock(worldX, worldY, worldZ);
                        Block feet = level.getBlock(worldX, worldY - 1, worldZ);
                        boolean passable = body.isTransparent() && head.isTransparent() && feet.isSolid();
                        walkable[x][y][z] = passable;
                    }
                }
            }
            return new WalkableSnapshot(radius, center.getFloorX(), center.getFloorY(), center.getFloorZ(), walkable);
        }

        boolean isWalkable(Vector3 position) {
            int sx = position.getFloorX() - (centerX - radius);
            int sy = position.getFloorY() - (centerY - radius);
            int sz = position.getFloorZ() - (centerZ - radius);
            if (sx < 0 || sy < 0 || sz < 0) {
                return false;
            }
            if (sx >= walkable.length || sy >= walkable[0].length || sz >= walkable[0][0].length) {
                return false;
            }
            return walkable[sx][sy][sz];
        }
    }

    private boolean inventoryHasSpace() {
        return entity.getInventory().getContents().size() < MAX_INVENTORY;
    }

    private boolean isChunkLoaded(Level level, Vector3 position) {
        return level.isChunkLoaded(position.getFloorX() >> 4, position.getFloorZ() >> 4);
    }
}
