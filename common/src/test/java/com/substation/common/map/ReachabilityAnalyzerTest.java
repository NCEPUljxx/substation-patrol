package com.substation.common.map;

import com.substation.common.model.Point;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachabilityAnalyzerTest {

    @Test
    void sealedCell_enclosedByObstacles() {
        boolean[][] obstacles = {
            {true, true, true, true, true},
            {true, false, true, false, true},
            {true, true, false, true, true},
            {true, false, true, false, true},
            {true, true, true, true, true}
        };
        List<Point> starts = List.of(new Point(1, 1));

        boolean[][] sealed = ReachabilityAnalyzer.findSealedFreeCells(obstacles, starts);

        assertTrue(sealed[2][2], "中心被障碍包围的格子应标记为密封");
        assertFalse(sealed[1][1], "起点可达，不应密封");
    }

    @Test
    void noSealed_whenAllFreeCellsReachable() {
        boolean[][] obstacles = {
            {false, false, false},
            {false, false, false},
            {false, false, false}
        };
        List<Point> starts = List.of(new Point(0, 0));

        boolean[][] sealed = ReachabilityAnalyzer.findSealedFreeCells(obstacles, starts);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                assertFalse(sealed[row][col]);
            }
        }
    }
}
