package com.substation.common.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrontierCellFinderTest {

  @Test
  void findsCellsAdjacentToExploredRegion() {
    int width = 5;
    int height = 5;
    boolean[][] explored = new boolean[height][width];
    boolean[][] obstacles = new boolean[height][width];
    boolean[][] sealed = new boolean[height][width];

    explored[2][2] = true;
    explored[2][1] = true;

    var frontiers = FrontierCellFinder.findFrontierCells(explored, obstacles, sealed, width, height);

    assertTrue(frontiers.contains(new com.substation.common.model.Point(2, 3)));
    assertTrue(frontiers.contains(new com.substation.common.model.Point(3, 2)));
    assertFalse(frontiers.contains(new com.substation.common.model.Point(4, 4)),
        "远离已探索区的未探索格不是前沿");
  }
}
