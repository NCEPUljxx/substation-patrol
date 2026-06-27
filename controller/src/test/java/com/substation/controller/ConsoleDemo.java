package com.substation.controller;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.mq.MessageBuilder;
import com.substation.common.mq.MessageTypes;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.*;

/**
 * 控制台可视化集成 Demo。
 * 启动真实 Controller + stub TP/Nav/Car，控制台实时渲染 30x30 地图。
 * 在 IDE 中直接运行 main() 即可。
 */
public class ConsoleDemo {

    private static final int MAP_SIZE = 30;
    private static final double OBSTACLE_RATIO = 0.15;
    private static final int CAR_COUNT = 3;
    private static final String ALGORITHM = "BFS";
    private static final int[][] DIRS = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

    private static volatile boolean running = true;

    public static void main(String[] args) throws Exception {
        // 1. 手动初始化 Redis（模拟 TaskConfigurator）
        setupRedis();

        // 2. 启动 Controller（等待 TASK_READY，tick 循环未开始）
        Thread controllerThread = new Thread(() -> {
            try {
                ControllerMain.main(args);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "controller-main");
        controllerThread.setDaemon(true);
        controllerThread.start();
        Thread.sleep(800);

        // 3. 启动 stub 模块
        startStubTargetPlanner();
        startStubNavigator();
        for (int i = 1; i <= CAR_COUNT; i++) {
            startStubCar(carId(i));
        }
        Thread.sleep(500);

        // 4. 发送 TASK_READY，触发 Controller 开始调度
        MessageBus kickBus = new MessageBus("localhost", 5672, "guest", "guest");
        kickBus.connect();
        kickBus.declareControllerQueue();
        kickBus.publish(QueueNames.CONTROLLER_CMD,
            MessageBuilder.build(MessageTypes.TASK_READY, 0));
        kickBus.close();

        // 5. 渲染循环
        BlackboardClient viewer = new BlackboardClient("localhost", 6379, MAP_SIZE, MAP_SIZE);
        System.out.println("\n");
        Thread.sleep(500);

        while (running) {
            int rate = viewer.getExplorationRate();
            clearScreen();
            render(viewer, rate);
            if (rate >= 100) {
                System.out.println("\n>>> 探索完成！探索率 = " + rate + "%");
                running = false;
            }
            Thread.sleep(600);
        }
        viewer.close();
        System.exit(0);
    }

    // ==================== Redis 初始化（替代 TaskConfigurator）====================

    private static void setupRedis() {
        try (Jedis jedis = new JedisPool("localhost", 6379).getResource()) {
            jedis.flushDB();
            jedis.del("controller:instance");
        }

        BlackboardClient bb = new BlackboardClient("localhost", 6379, MAP_SIZE, MAP_SIZE);
        Random rng = new Random(42);

        // TaskConfig
        bb.initTaskConfig(Map.of(
            "mapWidth", String.valueOf(MAP_SIZE),
            "mapHeight", String.valueOf(MAP_SIZE),
            "carCount", String.valueOf(CAR_COUNT),
            "obstacleRatio", String.valueOf(OBSTACLE_RATIO),
            "algorithm", ALGORITHM,
            "tickInterval", "500",
            "active", "true"
        ));

        // 随机障碍物
        int totalCells = MAP_SIZE * MAP_SIZE;
        int obstacleCount = (int) (totalCells * OBSTACLE_RATIO);
        Set<Integer> obstacleIndices = new HashSet<>();
        while (obstacleIndices.size() < obstacleCount) {
            obstacleIndices.add(rng.nextInt(totalCells));
        }
        for (int idx : obstacleIndices) {
            int r = idx / MAP_SIZE, c = idx % MAP_SIZE;
            bb.setBlock(r, c, true);
        }

        // 随机放置小车
        for (int i = 1; i <= CAR_COUNT; i++) {
            Point pos;
            do {
                pos = new Point(rng.nextInt(MAP_SIZE), rng.nextInt(MAP_SIZE));
            } while (bb.isBlocked(pos.y(), pos.x()));

            String carId = carId(i);
            bb.setCarPosition(carId, pos);
            bb.setCarStatus(carId, CarStatus.IDLE);
            bb.setCarSteps(carId, 0);
            illuminate(pos, bb);
        }
        bb.close();
    }

    // ==================== Stub: TargetPlanner ====================

    private static void startStubTargetPlanner() {
        new Thread(() -> {
            try {
                BlackboardClient bb = new BlackboardClient("localhost", 6379, MAP_SIZE, MAP_SIZE);
                MessageBus bus = new MessageBus("localhost", 5672, "guest", "guest");
                bus.connect();
                bus.declareTargetPlannerQueue();
                bus.subscribe(QueueNames.TARGET_PLANNER_CMD, raw -> {
                    JSONObject msg = JSONObject.parse(raw);
                    String carId = msg.getString("carId");
                    int tick = msg.getIntValue("tick");
                    bb.getCarPosition(carId).ifPresent(pos -> {
                        Point target = pickFarthestUnexplored(pos, bb);
                        boolean ok = target != null;
                        if (ok) bb.setCarTarget(carId, target);
                        Map<String, Object> data = Map.of(
                            "carId", carId, "success", ok,
                            "target", ok ? Map.of("x", target.x(), "y", target.y()) : Map.of()
                        );
                        try {
                            bus.publish(QueueNames.CONTROLLER_CMD,
                                MessageBuilder.build(MessageTypes.TARGET_ASSIGNED, tick, carId, data));
                        } catch (Exception ignored) {}
                    });
                });
            } catch (Exception e) { e.printStackTrace(); }
        }, "stub-tp").start();
    }

    private static Point pickFarthestUnexplored(Point from, BlackboardClient bb) {
        List<Point> candidates = new ArrayList<>();
        for (int r = 0; r < MAP_SIZE; r++)
            for (int c = 0; c < MAP_SIZE; c++)
                if (!bb.getMapViewBit(r, c) && !bb.isBlocked(r, c))
                    candidates.add(new Point(c, r));
        if (candidates.isEmpty()) return null;
        candidates.sort((a, b) -> b.manhattanDistance(from) - a.manhattanDistance(from));
        return candidates.get(0);
    }

    // ==================== Stub: Navigator ====================

    private static void startStubNavigator() {
        new Thread(() -> {
            try {
                BlackboardClient bb = new BlackboardClient("localhost", 6379, MAP_SIZE, MAP_SIZE);
                MessageBus bus = new MessageBus("localhost", 5672, "guest", "guest");
                bus.connect();
                bus.declareNavigatorQueue();
                bus.subscribe(QueueNames.NAVIGATOR_CMD, raw -> {
                    JSONObject msg = JSONObject.parse(raw);
                    String carId = msg.getString("carId");
                    int tick = msg.getIntValue("tick");
                    JSONObject d = msg.getJSONObject("data");
                    Point s = new Point(d.getJSONObject("start").getIntValue("x"),
                                       d.getJSONObject("start").getIntValue("y"));
                    Point t = new Point(d.getJSONObject("target").getIntValue("x"),
                                       d.getJSONObject("target").getIntValue("y"));
                    List<Point> path = bfs(s, t, bb);
                    if (!path.isEmpty()) bb.pushRoute(carId, path);
                    try {
                        bus.publish(QueueNames.CONTROLLER_CMD,
                            MessageBuilder.build(MessageTypes.ROUTE_PLANNED, tick, carId,
                                Map.of("carId", carId, "routeFound", !path.isEmpty(), "routeLength", path.size())));
                    } catch (Exception ignored) {}
                });
            } catch (Exception e) { e.printStackTrace(); }
        }, "stub-nav").start();
    }

    private static List<Point> bfs(Point start, Point target, BlackboardClient bb) {
        boolean[][] visited = new boolean[MAP_SIZE][MAP_SIZE];
        for (int r = 0; r < MAP_SIZE; r++)
            for (int c = 0; c < MAP_SIZE; c++)
                if (bb.isBlocked(r, c)) visited[r][c] = true;

        Map<Point, Point> parent = new HashMap<>();
        Queue<Point> queue = new LinkedList<>();
        queue.add(start);
        visited[start.y()][start.x()] = true;

        while (!queue.isEmpty()) {
            Point cur = queue.poll();
            if (cur.equals(target)) {
                List<Point> path = new ArrayList<>();
                for (Point p = target; !p.equals(start); p = parent.get(p))
                    path.add(0, p);
                return path;
            }
            for (int[] d : DIRS) {
                int nx = cur.x() + d[0], ny = cur.y() + d[1];
                if (nx >= 0 && nx < MAP_SIZE && ny >= 0 && ny < MAP_SIZE && !visited[ny][nx]) {
                    visited[ny][nx] = true;
                    Point next = new Point(nx, ny);
                    queue.add(next);
                    parent.put(next, cur);
                }
            }
        }
        return Collections.emptyList();
    }

    // ==================== Stub: Car ====================

    private static void startStubCar(String carId) {
        new Thread(() -> {
            try {
                BlackboardClient bb = new BlackboardClient("localhost", 6379, MAP_SIZE, MAP_SIZE);
                MessageBus bus = new MessageBus("localhost", 5672, "guest", "guest");
                bus.connect();
                bus.declareCarQueue(carId);
                bus.subscribe(QueueNames.carQueue(carId), raw -> {
                    JSONObject msg = JSONObject.parse(raw);
                    String type = msg.getString("type");
                    int tick = msg.getIntValue("tick");
                    if (!MessageTypes.TICK_MOVE.equals(type)) return;

                    bb.getCarPosition(carId).ifPresent(pos ->
                        bb.peekNextRouteStep(carId).ifPresentOrElse(next -> {
                            if (bb.isBlocked(next.y(), next.x())) {
                                bb.setCarStatus(carId, CarStatus.BLOCKED);
                                bb.setBlockedTick(carId, tick);
                                bb.clearRoute(carId);
                                publish(bus, MessageBuilder.build(MessageTypes.BLOCKED, tick, carId,
                                    Map.of("carId", carId, "blockedPosition",
                                        Map.of("x", next.x(), "y", next.y()), "blockedTick", tick)));
                            } else {
                                bb.popNextRouteStep(carId);
                                bb.setCarPosition(carId, next);
                                illuminate(next, bb);
                                bb.incrementCarSteps(carId);
                                boolean hasMore = bb.peekNextRouteStep(carId).isPresent();
                                bb.setCarStatus(carId, hasMore ? CarStatus.READY : CarStatus.IDLE);
                                String replyType = hasMore ? MessageTypes.MOVED : MessageTypes.ROUTE_DONE;
                                publish(bus, MessageBuilder.build(replyType, tick, carId,
                                    Map.of("carId", carId, "newPosition",
                                        Map.of("x", next.x(), "y", next.y()))));
                            }
                        }, () -> bb.setCarStatus(carId, CarStatus.IDLE))
                    );
                });
            } catch (Exception e) { e.printStackTrace(); }
        }, "stub-car-" + carId).start();
    }

    private static void publish(MessageBus bus, String msg) {
        try { bus.publish(QueueNames.CONTROLLER_CMD, msg); } catch (Exception ignored) {}
    }

    private static void illuminate(Point center, BlackboardClient bb) {
        for (int dr = -1; dr <= 1; dr++)
            for (int dc = -1; dc <= 1; dc++) {
                int r = center.y() + dr, c = center.x() + dc;
                if (r >= 0 && r < MAP_SIZE && c >= 0 && c < MAP_SIZE) {
                    bb.setMapViewBit(r, c, true);
                    bb.incrementMapHeat(r, c);
                }
            }
    }

    // ==================== 渲染 ====================

    private static void render(BlackboardClient bb, int rate) {
        Set<String> carIds = bb.discoverCarIds();
        Map<Point, String> carMarkers = new HashMap<>();
        Map<Point, CarStatus> carStatuses = new HashMap<>();
        for (String cid : carIds) {
            bb.getCarPosition(cid).ifPresent(p -> {
                carMarkers.put(p, cid.replace("Car", ""));
                carStatuses.put(p, bb.getCarStatus(cid).orElse(CarStatus.IDLE));
            });
        }

        StringBuilder sb = new StringBuilder();
        sb.append("+").append("-".repeat(MAP_SIZE * 2)).append("+\n");

        for (int r = 0; r < MAP_SIZE; r++) {
            sb.append("|");
            for (int c = 0; c < MAP_SIZE; c++) {
                Point p = new Point(c, r);
                String marker = carMarkers.get(p);
                if (marker != null) {
                    sb.append(markerStyle(marker, carStatuses.get(p)));
                } else if (bb.isBlocked(r, c)) {
                    sb.append("##");
                } else if (bb.getMapViewBit(r, c)) {
                    sb.append("  ");
                } else {
                    sb.append("..");
                }
            }
            sb.append("|\n");
        }

        sb.append("+").append("-".repeat(MAP_SIZE * 2)).append("+\n");
        sb.append(String.format("  探索率: %d%%  |  车辆: %d  |  Ctrl+C 退出", rate, carIds.size()));
        System.out.println(sb);
    }

    private static String markerStyle(String num, CarStatus st) {
        return switch (st) {
            case IDLE -> "[" + num + "]";
            case MOVING -> "<" + num + ">";
            case READY -> "(" + num + ")";
            case BLOCKED -> "!" + num + "!";
            case WAITING_ROUTE -> "?" + num + "?";
        };
    }

    private static void clearScreen() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    private static String carId(int n) { return String.format("Car%03d", n); }
}
