package com.substation.common.map;

import com.substation.common.model.Point;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 对比纯最短路 vs 偏好未探索加权路径在「已探索走廊」场景下的空跑步数 */
class ExplorationPathComparisonTest {

  @Test
  void weightedPathCrossesFewerExploredCellsThanHopShortest() {
    int width = 7;
    int height = 3;
    boolean[][] blocked = new boolean[height][width];
    boolean[][] explored = new boolean[height][width];

  // 顶行已探索；中行为已探索走廊；底行未探索
    for (int col = 0; col < width; col++) {
      explored[0][col] = true;
      explored[1][col] = col < width - 1;
    }

    Point start = new Point(0, 1);
    Point target = new Point(6, 1);

    List<Point> hopPath = ShortestHopPathFinder.plan(start, target, blocked, width, height);
    List<Point> weightedPath = ExplorationWeightedPathFinder.plan(
        start, target, blocked, explored, width, height,
        ExplorationWeightedPathFinder.SearchMode.WEIGHTED_DIJKSTRA);

    assertFalse(hopPath.isEmpty());
    assertFalse(weightedPath.isEmpty());
    assertEquals(target, hopPath.get(hopPath.size() - 1));
    assertEquals(target, weightedPath.get(weightedPath.size() - 1));

    int hopExploredSteps = countExploredSteps(hopPath, explored);
    int weightedExploredSteps = countExploredSteps(weightedPath, explored);

    assertTrue(hopExploredSteps > weightedExploredSteps,
        "最短路应穿过更多已探索格: hop=" + hopExploredSteps + ", weighted=" + weightedExploredSteps);
    assertEquals(0, weightedExploredSteps,
        "加权路径应走底行未探索带，路径上无已探索格");
  }

  @Test
  void weightedAndHopMatchWhenNoExploredCells() {
    int width = 8;
    int height = 8;
    boolean[][] blocked = new boolean[height][width];
    boolean[][] explored = new boolean[height][width];

    Point start = new Point(0, 0);
    Point target = new Point(5, 0);

    List<Point> hopPath = ShortestHopPathFinder.plan(start, target, blocked, width, height);
    List<Point> weightedPath = ExplorationWeightedPathFinder.plan(
        start, target, blocked, explored, width, height,
        ExplorationWeightedPathFinder.SearchMode.WEIGHTED_ASTAR);

    assertEquals(hopPath.size(), weightedPath.size(),
        "全图未探索时加权路径长度应等于 hop 最短路");
  }

  private static int countExploredSteps(List<Point> path, boolean[][] explored) {
    int count = 0;
    for (Point step : path) {
      if (explored[step.y()][step.x()]) {
        count++;
      }
    }
    return count;
  }
}
