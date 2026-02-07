package com.nuti.traffic.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CsvTicksWriter {

    public void write(Path path, int[] moved, int[] stopped) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter w = Files.newBufferedWriter(path)) {
                w.write("tick,moved,stopped");
                w.newLine();
                for (int t = 0; t < moved.length; t++) {
                    w.write(Integer.toString(t));
                    w.write(',');
                    w.write(Integer.toString(moved[t]));
                    w.write(',');
                    w.write(Integer.toString(stopped[t]));
                    w.newLine();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write ticks CSV: " + path, e);
        }
    }
}
