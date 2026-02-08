package com.nukit.npchelper;

import cn.nukkit.math.Vector3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public final class PathfindingTask {
    private PathfindingTask() {
    }

    public static List<Vector3> computePath(NpcHelperAI.WalkableSnapshot snapshot, Vector3 start, Vector3 target) {
        if (snapshot == null || start == null || target == null) {
            return List.of();
        }
        Node startNode = Node.from(snapshot, start);
        Node targetNode = Node.from(snapshot, target);
        if (startNode == null || targetNode == null) {
            return List.of();
        }

        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Node, Node> cameFrom = new HashMap<>();
        Map<Node, Double> gScore = new HashMap<>();

        gScore.put(startNode, 0.0);
        startNode.f = heuristic(startNode, targetNode);
        openSet.add(startNode);

        while (!openSet.isEmpty()) {
            Node current = openSet.poll();
            if (current.equals(targetNode)) {
                return reconstruct(snapshot, cameFrom, current);
            }
            for (Node neighbor : current.neighbors(snapshot)) {
                double tentative = gScore.getOrDefault(current, Double.MAX_VALUE) + current.distance(neighbor);
                if (tentative < gScore.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    cameFrom.put(neighbor, current);
                    gScore.put(neighbor, tentative);
                    neighbor.f = tentative + heuristic(neighbor, targetNode);
                    if (!openSet.contains(neighbor)) {
                        openSet.add(neighbor);
                    }
                }
            }
        }
        return List.of();
    }

    private static double heuristic(Node a, Node b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y) + Math.abs(a.z - b.z);
    }

    private static List<Vector3> reconstruct(NpcHelperAI.WalkableSnapshot snapshot, Map<Node, Node> cameFrom, Node current) {
        List<Vector3> path = new ArrayList<>();
        path.add(snapshotToWorld(snapshot, current));
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            path.add(0, snapshotToWorld(snapshot, current));
        }
        return path;
    }

    private static Vector3 snapshotToWorld(NpcHelperAI.WalkableSnapshot snapshot, Node node) {
        int worldX = snapshot.centerX() - snapshot.radius() + node.x;
        int worldY = snapshot.centerY() - snapshot.radius() + node.y;
        int worldZ = snapshot.centerZ() - snapshot.radius() + node.z;
        return new Vector3(worldX + 0.5, worldY, worldZ + 0.5);
    }

    private static final class Node {
        private final int x;
        private final int y;
        private final int z;
        private double f;

        private Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        static Node from(NpcHelperAI.WalkableSnapshot snapshot, Vector3 vector) {
            int sx = vector.getFloorX() - (snapshot.centerX() - snapshot.radius());
            int sy = vector.getFloorY() - (snapshot.centerY() - snapshot.radius());
            int sz = vector.getFloorZ() - (snapshot.centerZ() - snapshot.radius());
            if (sx < 0 || sy < 0 || sz < 0) {
                return null;
            }
            if (sx >= snapshot.walkable().length || sy >= snapshot.walkable()[0].length || sz >= snapshot.walkable()[0][0].length) {
                return null;
            }
            return new Node(sx, sy, sz);
        }

        List<Node> neighbors(NpcHelperAI.WalkableSnapshot snapshot) {
            List<Node> neighbors = new ArrayList<>();
            int[][] directions = {
                    {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
                    {0, 1, 0}, {0, -1, 0}
            };
            for (int[] dir : directions) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                int nz = z + dir[2];
                if (nx >= 0 && ny >= 0 && nz >= 0
                        && nx < snapshot.walkable().length
                        && ny < snapshot.walkable()[0].length
                        && nz < snapshot.walkable()[0][0].length
                        && snapshot.walkable()[nx][ny][nz]) {
                    neighbors.add(new Node(nx, ny, nz));
                }
            }
            return neighbors;
        }

        double distance(Node other) {
            return Math.sqrt(Math.pow(x - other.x, 2) + Math.pow(y - other.y, 2) + Math.pow(z - other.z, 2));
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            Node node = (Node) obj;
            return x == node.x && y == node.y && z == node.z;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(x);
            result = 31 * result + Integer.hashCode(y);
            result = 31 * result + Integer.hashCode(z);
            return result;
        }
    }
}
