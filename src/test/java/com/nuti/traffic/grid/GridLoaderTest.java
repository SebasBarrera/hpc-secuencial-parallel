package com.nuti.traffic.grid;

import com.nuti.traffic.model.Grid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GridLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void load_validExampleGrid_doesNotThrow() {
        GridLoader loader = new GridLoader();
        assertDoesNotThrow(() -> loader.load(Path.of("grids", "ejemplo1.txt")));
    }

    @Test
    void load_nonRectangular_throws() throws IOException {
        Path p = tempDir.resolve("nonrect.txt");
        Files.writeString(p, "####\n###\n####\n");

        GridLoader loader = new GridLoader();
        assertThrows(GridValidationException.class, () -> loader.load(p));
    }

    @Test
    void load_invalidCharacter_throws() throws IOException {
        Path p = tempDir.resolve("badchar.txt");
        Files.writeString(p, "#####\n#X###\n#####\n");

        GridLoader loader = new GridLoader();
        assertThrows(GridValidationException.class, () -> loader.load(p));
    }

    @Test
    void load_borderNotBlocks_throws() throws IOException {
        Path p = tempDir.resolve("border.txt");
        Files.writeString(p, "..#\n###\n###\n");

        GridLoader loader = new GridLoader();
        assertThrows(GridValidationException.class, () -> loader.load(p));
    }

    @Test
    void load_intersectionWithoutPerpendicularConnectivity_throws() throws IOException {
        Path p = tempDir.resolve("badplus.txt");
        Files.writeString(p,
                "#####\n" +
                "#####\n" +
                "##+##\n" +
                "#####\n" +
                "#####\n"
        );

        GridLoader loader = new GridLoader();
        assertThrows(GridValidationException.class, () -> loader.load(p));
    }

    @Test
    void load_roadWithWrongDegree_throws() throws IOException {
        Path p = tempDir.resolve("baddot.txt");
        Files.writeString(p,
                "#####\n" +
                "#####\n" +
                "##.##\n" +
                "#####\n" +
                "#####\n"
        );

        GridLoader loader = new GridLoader();
        assertThrows(GridValidationException.class, () -> loader.load(p));
    }

    @Test
    void load_validBigGrid_returnsGrid() {
        GridLoader loader = new GridLoader();
        Grid g = loader.load(Path.of("grids", "big.txt"));
        assertDoesNotThrow(() -> {
            g.width();
            g.height();
            g.cellCount();
            g.intersectionCount();
        });
    }
}
